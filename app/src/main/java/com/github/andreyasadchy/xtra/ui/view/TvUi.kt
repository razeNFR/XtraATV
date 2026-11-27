package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout

/** Adjustments applied to every screen on TV. */
object TvUi {

    fun isTv(context: Context) = context.resources.getBoolean(R.bool.isTv)

    @Volatile
    private var cachedUseTvUi: Boolean? = null

    fun invalidateCache() {
        cachedUseTvUi = null
    }

    /** Restarts the app into the newly selected interface mode (Auto / TV / Mobile). */
    fun restartAppForInterfaceMode(context: Context) {
        invalidateCache()
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return
        context.startActivity(intent)
        (context as? android.app.Activity)?.finishAffinity()
    }

    private fun readInterfaceModeOverride(context: Context): String {
        return runCatching {
            context.prefs().getString(com.github.andreyasadchy.xtra.util.C.UI_INTERFACE_MODE, "auto")
        }.getOrNull().orEmpty().ifBlank { "auto" }
    }

    private fun hasRealVibrator(context: Context): Boolean {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator?.hasVibrator() == true
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                v?.hasVibrator() == true
            }
        }.getOrDefault(false)
    }

    private fun isKnownTvBoxHardware(): Boolean {
        val signature = listOf(
            android.os.Build.HARDWARE,
            android.os.Build.BOARD,
            android.os.Build.MANUFACTURER,
            android.os.Build.BRAND,
            android.os.Build.MODEL,
            android.os.Build.DEVICE,
            android.os.Build.PRODUCT,
        ).joinToString(" ").lowercase(java.util.Locale.US)
        return TV_BOX_SIGNATURES.any { signature.contains(it) }
    }

    private val TV_BOX_SIGNATURES = listOf(
        "amlogic", "meson", "g12a", "g12b", "gxl", "gxm", "s905", "s912", "s922",
        "beelink", "gt1", "rockchip", "rk32", "rk33", "rk35", "sunxi", "allwinner",
        "h616", "h618", "tanix", "x96", "h96", "mecool", "minix", "ugoos",
        "formuler", "buzztv", "homatics", "tx3", "tx6", "mxq", "t95",
    )

    /**
     * Detects TVs and non-certified AOSP Android TV boxes while leaving real smartphones/tablets in mobile mode:
     * - Respects manual override (`ui_interface_mode`: "tv" / "mobile" / "auto")
     * - Certified TV / Fire TV / Leanback / HDMI-CEC devices
     * - Devices without a touchscreen (generic AOSP TV boxes)
     * - Non-certified AOSP boxes (e.g. Beelink Amlogic GT1mini-2 Android 8) that falsely declare touchscreen/battery:
     *   no camera + no vibrator combined with Ethernet, known TV box SoC/brand, or dummy/missing battery
     * - Devices without telephony and without a battery
     */
    fun shouldUseTvUi(context: Context): Boolean {
        cachedUseTvUi?.let { return it }
        when (readInterfaceModeOverride(context)) {
            "tv" -> {
                cachedUseTvUi = true
                return true
            }
            "mobile" -> {
                cachedUseTvUi = false
                return false
            }
        }
        val uiModeType = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        val uiModeMgrType = runCatching {
            (context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)?.currentModeType
        }.getOrNull()
        val pm = context.packageManager
        val hasTvFeature = uiModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            uiModeMgrType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            @Suppress("DEPRECATION") pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv") ||
            pm.hasSystemFeature("android.hardware.hdmi.cec")
        if (hasTvFeature) {
            cachedUseTvUi = true
            return true
        }
        val noTouchscreen = !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        if (noTouchscreen) {
            cachedUseTvUi = true
            return true
        }

        val batteryIntent = runCatching {
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val hasBattery = batteryIntent?.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, true)
        val dummyAcBattery = batteryIntent != null && run {
            val plugged = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
            val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val voltage = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)
            val temp = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
            plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC &&
                level == 100 &&
                (voltage <= 0 || temp <= 0)
        }

        val hasCamera = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY) ||
            @Suppress("DEPRECATION") pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA) ||
            pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FRONT)
        val hasVibrator = hasRealVibrator(context)
        val hasEthernet = pm.hasSystemFeature("android.hardware.ethernet")
        if (!hasCamera && !hasVibrator && (hasEthernet || isKnownTvBoxHardware() || hasBattery == false || dummyAcBattery)) {
            cachedUseTvUi = true
            return true
        }

        if (!pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)) {
            if (batteryIntent != null) {
                val result = hasBattery == false || dummyAcBattery
                cachedUseTvUi = result
                return result
            }
            // Don't cache false if sticky broadcast wasn't readable yet during early attachBaseContext
            return false
        }
        cachedUseTvUi = false
        return false
    }

    /**
     * Forces UI_MODE_TYPE_TELEVISION on TVs and non-certified AOSP Android TV boxes (which report UI_MODE_TYPE_NORMAL),
     * or UI_MODE_TYPE_NORMAL when manually forced to mobile mode, while preserving night mode and locale.
     */
    fun wrapTvContext(base: Context): Context {
        val useTv = shouldUseTvUi(base)
        val current = base.resources.configuration
        val currentType = current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        val desiredType = if (useTv) {
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        } else {
            android.content.res.Configuration.UI_MODE_TYPE_NORMAL
        }
        if (currentType == desiredType) {
            return base
        }
        // Only override an existing TELEVISION mode when the user explicitly forced "mobile"
        if (!useTv && readInterfaceModeOverride(base) != "mobile") {
            return base
        }
        val config = android.content.res.Configuration(current).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK.inv()) or desiredType
        }
        return base.createConfigurationContext(config)
    }

    /** Ensures AppCompat override configurations keep UI_MODE_TYPE_TELEVISION intact on TV devices without altering night mode. */
    fun enforceTvUiMode(config: android.content.res.Configuration?) {
        if (config == null || cachedUseTvUi != true) return
        if ((config.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) !=
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        ) {
            config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK.inv()) or
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        }
    }

    /**
     * Headers never scroll away (the D-pad could not bring them back), list rows get a clearly visible
     * focus highlight and, in the main screen, the toolbar actions moved to the navigation rail are hidden.
     */
    fun adapt(view: View, hideToolbarActions: Boolean) {
        // loading circles in the color of accent (the ambiance color of the Xtra TV settings)
        if (view is android.widget.ProgressBar && com.github.andreyasadchy.xtra.ui.tv.TvAccent.current != null) {
            view.indeterminateTintList = com.github.andreyasadchy.xtra.ui.tv.TvAccent.colorList
        }
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
                if (hideToolbarActions) {
                    // main screens: header drawn with Compose (refresh, sections, sort)
                    TvHeader.install(view)
                }
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
        if (view.id == R.id.sortBar) {
            view.post { addRefreshButton(view) }
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

    /** Tabs show a clear focus frame, they are only selected with OK. */
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
            // option of the Xtra TV settings: a tab shows its page after a short stop on it, without OK
            if (tab.context.prefs().getBoolean(com.github.andreyasadchy.xtra.util.C.TV_FOCUS_SWITCH, false)) {
                val select = Runnable { if (tab.hasFocus() && !tab.isSelected) tab.performClick() }
                tab.setOnFocusChangeListener { _, hasFocus ->
                    tab.removeCallbacks(select)
                    if (hasFocus) tab.postDelayed(select, 60L)
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
        if (view is android.widget.EditText && view.context.prefs().getBoolean(com.github.andreyasadchy.xtra.util.C.TV_DISABLE_SOFT_KEYBOARD, false)) {
            view.showSoftInputOnFocus = false
        }
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
                val r = 20f * density
                sheet.background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xE6161520.toInt(), 0xEE12111A.toInt()),
                ).apply {
                    cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                    setStroke(maxOf(1, density.toInt()), 0x26FFFFFF)
                }
                sheet.clipToOutline = true
                sheet.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                dialog.window?.setDimAmount(0.16f)
                sheet.setPadding((14 * density).toInt(), (24 * density).toInt(), (14 * density).toInt(), (24 * density).toInt())
                sheet.translationX = 460 * density
                sheet.animate().translationX(0f).setDuration(180L).start()
                styleSheetViews(sheet)
                // sort, filters and choices: drawn with Compose
                val content = fragment.view
                val isChoiceSheet = fragment.javaClass.simpleName.endsWith("SortDialog") ||
                    fragment is com.github.andreyasadchy.xtra.ui.common.RadioButtonDialogFragment ||
                    fragment is com.github.andreyasadchy.xtra.ui.common.SelectLanguagesDialog
                if (isChoiceSheet && content != null && sheet is ViewGroup && com.github.andreyasadchy.xtra.ui.tv.TvSheetCompose.canConvert(content)) {
                    val sheetTitle = when {
                        fragment is com.github.andreyasadchy.xtra.ui.common.SelectLanguagesDialog -> context.getString(R.string.languages)
                        fragment is com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog ||
                            fragment is com.github.andreyasadchy.xtra.ui.games.GamesSortDialog -> context.getString(R.string.sort_and_filter)
                        fragment.javaClass.simpleName.endsWith("SortDialog") -> context.getString(R.string.sort)
                        else -> null
                    }
                    com.github.andreyasadchy.xtra.ui.tv.TvSheetCompose.install(
                        sheet,
                        content,
                        sheetTitle,
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

    /**
     * "Refresh" pill next to the sort pill, in the header of every list (games, popular, following...):
     * reloads the list shown. It stays when the list has no sort, except on the search screen.
     */
    private fun addRefreshButton(sortBar: View) {
        val parent = sortBar.parent as? ViewGroup ?: return
        var ancestor: View? = parent
        while (ancestor != null) {
            if (ancestor is AppBarLayout && (ancestor.findViewById<View>(R.id.tvHeaderCompose) != null || ancestor.findViewById<View>(R.id.searchView) != null)) {
                // the Compose header already has its refresh button, and the search screen has none
                return
            }
            ancestor = ancestor.parent as? View
        }
        if (parent.id == R.id.tvHeaderActions) {
            return
        }
        val context = sortBar.context
        val density = context.resources.displayMetrics.density
        val index = parent.indexOfChild(sortBar)
        val params = sortBar.layoutParams
        val wrapper = android.widget.LinearLayout(context).apply {
            id = R.id.tvHeaderActions
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        parent.removeViewAt(index)
        parent.addView(wrapper, index, params)
        val refresh = TextView(context).apply {
            text = context.getString(R.string.refresh)
            textSize = 15f
            setTextColor(context.getColorStateList(R.color.tv_pill_text))
            setBackgroundResource(R.drawable.tv_pill_background)
            val icon = context.getDrawable(R.drawable.tv_ic_refresh)?.mutate()?.apply {
                setTintList(context.getColorStateList(R.color.tv_pill_text))
                setBounds(0, 0, (20 * density).toInt(), (20 * density).toInt())
            }
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = (10 * density).toInt()
            setPadding((18 * density).toInt(), (9 * density).toInt(), (20 * density).toInt(), (9 * density).toInt())
            isFocusable = true
            isClickable = true
            setOnClickListener { refreshVisibleList(wrapper) }
        }
        wrapper.addView(refresh, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (12 * density).toInt()
        })
        wrapper.addView(sortBar, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A list is loaded again with the refresh button: its icon turns (at least a moment, so it is always seen). */
    val refreshing = androidx.compose.runtime.mutableStateOf(false)

    fun refreshVisibleList(from: View) {
        // the screen the button is in: up to the root of the fragment
        var root: View = from
        while (true) {
            val parent = root.parent as? View ?: break
            if (parent.id == R.id.navHostFragment || parent is androidx.fragment.app.FragmentContainerView) break
            root = parent
        }
        val grid = findVisibleGrid(root) ?: return
        val adapter = grid.adapter as? androidx.paging.PagingDataAdapter<*, *>
        if (adapter == null) {
            grid.scrollToPosition(0)
            return
        }
        if (refreshing.value) {
            return
        }
        refreshing.value = true
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var loading = false
        val finish = {
            // the cards stay: a light sweeps over them once and a short message says it is done
            val wait = (800L - (android.os.SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
            grid.postDelayed({
                refreshing.value = false
                if (grid.isAttachedToWindow) {
                    grid.sweep()
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(grid.context, R.string.tv_refreshed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }, wait)
        }
        val self = arrayOfNulls<(androidx.paging.CombinedLoadStates) -> Unit>(1)
        val listener: (androidx.paging.CombinedLoadStates) -> Unit = { states ->
            when (states.refresh) {
                is androidx.paging.LoadState.Loading -> loading = true
                else -> if (loading) {
                    loading = false
                    self[0]?.let { adapter.removeLoadStateListener(it) }
                    finish()
                }
            }
        }
        self[0] = listener
        adapter.addLoadStateListener(listener)
        // never turning for ever (the list had nothing to load again)
        grid.postDelayed({
            if (refreshing.value && !loading) {
                adapter.removeLoadStateListener(listener)
                finish()
            }
        }, 5000L)
        adapter.refresh()
        grid.scrollToPosition(0)
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

    /** Focuses the first card fully on screen (the list is not moved). */
    fun focusFirstVisibleItem(grid: GridRecyclerView): Boolean {
        val rect = android.graphics.Rect()
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child.isShown && child.getGlobalVisibleRect(rect) && rect.height() >= child.height * 0.9f && child.requestFocus()) {
                return true
            }
        }
        return focusFirstItem(grid)
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
                if (view.foreground == null && view.findViewById<View>(R.id.delete) == null) {
                    view.foreground = view.context.getDrawable(R.drawable.tv_row_focus)
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }
}
