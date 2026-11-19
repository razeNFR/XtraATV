package com.github.andreyasadchy.xtra.ui.following

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMediaPagerBinding
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class FollowPagerFragment : Fragment(), Scrollable, FragmentHost {

    companion object {
        /** TV side menu: tab to show when the screen opens ("0" categories, "2" videos, "3" channels). */
        var requestedTab: String? = null
        var clearStandalone: Boolean = false
    }

    private var _binding: FragmentMediaPagerBinding? = null
    private val binding get() = _binding!!
    private var firstLaunch = true

    /** Keys of the tabs shown in the header ("0" categories, "1" live, "2" videos, "3" channels). */
    private var shownTabs: List<String> = emptyList()
    /** Keys of all tabs mounted inside [ViewPager2] (on TV, all available tabs stay permanently mounted). */
    private var pagerTabs: List<String> = emptyList()
    private var enabledTabsList: List<String> = emptyList()
    private var defaultTabKey: String = "1"
    private var tabMediator: TabLayoutMediator? = null

    private var pendingTab = -1

    /** A tab shown alone (asked from the side menu, not among the tabs of the screen), kept when the screen comes back. */
    private var standaloneTab: String? = null

    private fun isTv(): Boolean = context?.resources?.getBoolean(R.bool.isTv) == true

    private fun tabTitle(key: String?): String = when (key) {
        "0" -> getString(R.string.games)
        "1" -> getString(R.string.live)
        "2" -> getString(R.string.videos)
        "3" -> getString(R.string.channels)
        else -> getString(R.string.live)
    }

    private fun computeAvailableTvPagerTabs(enabled: List<String>, showVideosTab: Boolean): List<String> {
        val all = buildList {
            addAll(enabled)
            add("1")
            if (showVideosTab) add("2")
            add("3")
            add("0")
        }
        return all.distinct().filter { it != "2" || showVideosTab }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val index = pendingTab
        pendingTab = -1
        if (index >= 0) {
            binding.viewPager.post {
                if (_binding != null) {
                    binding.viewPager.setCurrentItem(index, false)
                }
            }
        }
    }

    fun activeTabKey(): String? {
        val b = _binding ?: return standaloneTab
        return standaloneTab ?: pagerTabs.getOrNull(b.viewPager.currentItem)
    }

    fun standaloneTabKey(): String? = standaloneTab

    /** TV side menu: goes to a tab of the screen already open, false when it is not shown there. */
    fun showTab(key: String): Boolean {
        val index = pagerTabs.indexOf(key)
        if (index >= 0 && _binding != null) {
            binding.viewPager.setCurrentItem(index, false)
            syncTvHeaderSelection(key)
            return true
        }
        return false
    }

    /**
     * Switches between full Following tabs (`key == null`) and any sub-entry (`"0"`, `"2"`, `"3"`)
     * directly in place without destroying or recreating [FollowPagerFragment] or its [FollowPagerAdapter].
     */
    fun selectMenuTab(key: String?): Boolean {
        val b = _binding ?: return false
        if (!isAdded) return false
        val showVideosTab = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank()
        if (key == "2" && !showVideosTab) return false

        if (isTv()) {
            val wantedPagerTabs = computeAvailableTvPagerTabs(enabledTabsList, showVideosTab)
            val targetKey = key ?: defaultTabKey
            standaloneTab = key?.takeIf { it !in enabledTabsList }
            val headerTabs = if (standaloneTab != null) listOf(standaloneTab!!) else enabledTabsList
            if (pagerTabs != wantedPagerTabs || b.viewPager.adapter == null) {
                bindTvPagerAndHeader(wantedPagerTabs, headerTabs, targetKey)
            } else {
                val targetIdx = pagerTabs.indexOf(targetKey).takeIf { it >= 0 }
                    ?: pagerTabs.indexOf("1").takeIf { it >= 0 }
                    ?: 0
                if (b.viewPager.currentItem != targetIdx) {
                    b.viewPager.setCurrentItem(targetIdx, false)
                }
                syncTvHeaderTabs(headerTabs, pagerTabs.getOrNull(b.viewPager.currentItem) ?: targetKey)
                syncSortBarForPosition(b.viewPager.currentItem)
            }
            scheduleActiveTabStaleRefresh(160L)
            com.github.andreyasadchy.xtra.ui.view.TvHeader.refresh(b.root)
            return true
        }

        if (key == null) {
            if (standaloneTab != null || shownTabs != enabledTabsList) {
                standaloneTab = null
                bindMobileTabs(enabledTabsList, defaultTabKey)
            } else {
                val defaultIdx = shownTabs.indexOf(defaultTabKey).takeIf { it >= 0 } ?: 0
                if (b.viewPager.currentItem != defaultIdx) {
                    b.viewPager.setCurrentItem(defaultIdx, false)
                }
            }
        } else {
            if (key in enabledTabsList) {
                if (standaloneTab != null || shownTabs != enabledTabsList) {
                    standaloneTab = null
                    bindMobileTabs(enabledTabsList, key)
                } else {
                    val idx = shownTabs.indexOf(key)
                    if (idx >= 0 && b.viewPager.currentItem != idx) {
                        b.viewPager.setCurrentItem(idx, false)
                    }
                }
            } else if (standaloneTab != key || shownTabs != listOf(key)) {
                standaloneTab = key
                bindMobileTabs(listOf(key), key)
            }
        }
        return true
    }

    private fun scheduleActiveTabStaleRefresh(delayMs: Long) {
        val root = _binding?.root ?: return
        root.postDelayed(object : Runnable {
            override fun run() {
                if (_binding == null || !isResumed) return
                val act = activity as? MainActivity
                if (act != null && act.isTvMenuOpen() && act.idleDurationMs() < 380L) {
                    _binding?.root?.postDelayed(this, 320L)
                    return
                }
                (currentFragment as? com.github.andreyasadchy.xtra.ui.common.PagedListFragment)?.refreshIfStale(20_000L)
            }
        }, delayMs)
    }

    private fun bindTvPagerAndHeader(allPagerTabs: List<String>, headerTabs: List<String>, targetKey: String?) {
        val b = _binding ?: return
        tabMediator?.detach()
        tabMediator = null
        pagerTabs = allPagerTabs
        val adapter = FollowPagerAdapter(this@FollowPagerFragment, allPagerTabs)
        b.viewPager.isUserInputEnabled = false
        b.viewPager.adapter = adapter
        b.viewPager.offscreenPageLimit = adapter.itemCount.coerceAtLeast(1)
        val activeKey = targetKey ?: defaultTabKey
        val idx = allPagerTabs.indexOf(activeKey).takeIf { it >= 0 }
            ?: allPagerTabs.indexOf("1").takeIf { it >= 0 }
            ?: 0
        b.viewPager.setCurrentItem(idx, false)
        syncTvHeaderTabs(headerTabs, allPagerTabs.getOrNull(idx) ?: activeKey)
    }

    private fun syncTvHeaderTabs(headerTabs: List<String>, activeKey: String?) {
        val b = _binding ?: return
        val needsRebuild = shownTabs != headerTabs || b.tabLayout.tabCount != headerTabs.size
        shownTabs = headerTabs
        if (headerTabs.size <= 1) {
            b.tabLayout.visibility = View.GONE
            if (needsRebuild) {
                b.tabLayout.removeAllTabs()
            }
            return
        }
        b.tabLayout.visibility = View.VISIBLE
        b.tabLayout.tabGravity = if (headerTabs.size >= 5) TabLayout.GRAVITY_CENTER else TabLayout.GRAVITY_FILL
        b.tabLayout.tabMode = if (headerTabs.size >= 5) TabLayout.MODE_SCROLLABLE else TabLayout.MODE_FIXED
        if (needsRebuild) {
            b.tabLayout.removeAllTabs()
            headerTabs.forEach { key ->
                val tab = b.tabLayout.newTab()
                tab.text = tabTitle(key)
                tab.tag = pagerTabs.indexOf(key).takeIf { it >= 0 } ?: 0
                b.tabLayout.addTab(tab, key == activeKey)
            }
        } else {
            headerTabs.forEachIndexed { idx, key ->
                b.tabLayout.getTabAt(idx)?.let { tab ->
                    tab.tag = pagerTabs.indexOf(key).takeIf { it >= 0 } ?: 0
                    val wantedTitle = tabTitle(key)
                    if (tab.text != wantedTitle) tab.text = wantedTitle
                    if (key == activeKey && !tab.isSelected) {
                        tab.select()
                    }
                }
            }
        }
    }

    private fun syncTvHeaderSelection(activeKey: String?) {
        val b = _binding ?: return
        if (shownTabs.size <= 1 || activeKey == null) return
        val headerIdx = shownTabs.indexOf(activeKey)
        if (headerIdx >= 0) {
            b.tabLayout.getTabAt(headerIdx)?.takeIf { !it.isSelected }?.select()
        }
    }

    private fun bindMobileTabs(tabs: List<String>, targetKey: String?) {
        val b = _binding ?: return
        tabMediator?.detach()
        tabMediator = null
        shownTabs = tabs
        pagerTabs = tabs
        if (tabs.size <= 1) {
            b.tabLayout.visibility = View.GONE
        } else {
            b.tabLayout.visibility = View.VISIBLE
            if (tabs.size >= 5) {
                b.tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
                b.tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
            } else {
                b.tabLayout.tabGravity = TabLayout.GRAVITY_FILL
                b.tabLayout.tabMode = TabLayout.MODE_FIXED
            }
        }
        val adapter = FollowPagerAdapter(this@FollowPagerFragment, tabs)
        b.viewPager.adapter = adapter
        b.viewPager.offscreenPageLimit = adapter.itemCount
        if (targetKey != null) {
            val idx = tabs.indexOf(targetKey).takeIf { it >= 0 }
                ?: tabs.indexOf("1").takeIf { it >= 0 }
                ?: 0
            b.viewPager.setCurrentItem(idx, false)
        }
        tabMediator = TabLayoutMediator(b.tabLayout, b.viewPager) { tab, position ->
            tab.text = tabTitle(tabs.getOrNull(position))
        }.also { it.attach() }
    }

    private fun syncSortBarForPosition(position: Int) {
        val b = _binding ?: return
        b.viewPager.doOnLayout {
            if (_binding == null || !isAdded) return@doOnLayout
            childFragmentManager.findFragmentByTag("f${b.viewPager.adapter?.getItemId(position) ?: position}")?.let { fragment ->
                if (isTv()) {
                    // TV: the header stays still like on a category page
                } else if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    fragment.view?.findViewById<RecyclerView>(R.id.recyclerView)?.let {
                        b.appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                b.appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            b.appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    b.appBar.setLiftable(false)
                    b.appBar.background = null
                }
                if (fragment is Sortable) {
                    fragment.setupSortBar(b.sortBar)
                } else {
                    b.sortBar.root.visibility = View.GONE
                }
                if (isTv()) {
                    com.github.andreyasadchy.xtra.ui.view.TvHeader.refresh(b.root)
                }
            }
        }
    }

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.adapter?.getItemId(binding.viewPager.currentItem) ?: binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaPagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val activity = requireActivity() as MainActivity
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.search -> {
                        findNavController().navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                        true
                    }
                    R.id.settings -> {
                        activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
                        true
                    }
                    R.id.login -> {
                        if (isLoggedIn) {
                            activity.getAlertDialogBuilder().apply {
                                setTitle(getString(R.string.logout_title))
                                requireContext().tokenPrefs().getString(C.USERNAME, null)?.let { setMessage(getString(R.string.logout_msg, it)) }
                                setNegativeButton(getString(R.string.no), null)
                                setPositiveButton(getString(R.string.yes)) { _, _ -> activity.logoutResultLauncher?.launch(Intent(activity, LoginActivity::class.java)) }
                            }.show()
                        } else {
                            activity.loginResultLauncher?.launch(Intent(activity, LoginActivity::class.java))
                        }
                        true
                    }
                    else -> false
                }
            }
            val showVideosTab = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank()
            val tabList = requireContext().prefs().getString(C.UI_FOLLOWING_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_FOLLOWING_TABS.split(',')
                if (tabPref != null) {
                    val list = tabPref.split(',').filter { item ->
                        defaultTabs.find { it.first() == item.first() } != null
                    }.toMutableList()
                    defaultTabs.forEachIndexed { index, item ->
                        if (list.find { it.first() == item.first() } == null) {
                            list.add(index, item)
                        }
                    }
                    list
                } else defaultTabs
            }
            val enabledTabs = tabList.mapNotNull {
                val split = it.split(':')
                val key = split[0]
                val enabled = split[2] != "0"
                if (enabled && (key != "2" || showVideosTab)) key else null
            }
            enabledTabsList = enabledTabs
            defaultTabKey = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
            val wasClearStandalone = clearStandalone
            if (clearStandalone) {
                clearStandalone = false
                standaloneTab = null
            }
            val requested = (requestedTab?.also { requestedTab = null } ?: standaloneTab)?.takeIf { it != "2" || showVideosTab }
            standaloneTab = requested?.takeIf { it !in enabledTabs }
            val headerTabs = if (requested != null && requested !in enabledTabs) listOf(requested) else enabledTabs

            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (isTv()) {
                        val key = pagerTabs.getOrNull(position)
                        syncTvHeaderSelection(key)
                        (activity as? MainActivity)?.onFollowTabChanged(key)
                    }
                    syncSortBarForPosition(position)
                }
            })
            viewPager.reduceDragSensitivity()
            val targetKey = requested ?: if (firstLaunch || wasClearStandalone) defaultTabKey else null
            if (isTv()) {
                val allPagerTabs = computeAvailableTvPagerTabs(enabledTabs, showVideosTab)
                bindTvPagerAndHeader(allPagerTabs, headerTabs, targetKey)
            } else {
                bindMobileTabs(headerTabs, targetKey)
            }
            if (requested != null) {
                pendingTab = pagerTabs.indexOf(requested)
                firstLaunch = false
            } else if (wasClearStandalone) {
                pendingTab = pagerTabs.indexOf(defaultTabKey).coerceAtLeast(0)
                firstLaunch = false
            } else if (firstLaunch) {
                firstLaunch = false
            }
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                windowInsets
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        val showVideosTab = !TwitchApiHelper.getGQLHeaders(ctx, true)[C.HEADER_TOKEN].isNullOrBlank()
        val tabList = ctx.prefs().getString(C.UI_FOLLOWING_TABS, null).let { tabPref ->
            val defaultTabs = C.DEFAULT_FOLLOWING_TABS.split(',')
            if (tabPref != null) {
                val list = tabPref.split(',').filter { item ->
                    defaultTabs.find { it.first() == item.first() } != null
                }.toMutableList()
                defaultTabs.forEachIndexed { index, item ->
                    if (list.find { it.first() == item.first() } == null) {
                        list.add(index, item)
                    }
                }
                list
            } else defaultTabs
        }
        enabledTabsList = tabList.mapNotNull {
            val split = it.split(':')
            val key = split[0]
            val enabled = split[2] != "0"
            if (enabled && (key != "2" || showVideosTab)) key else null
        }
        defaultTabKey = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
        val req = requestedTab
        if (req != null) {
            requestedTab = null
            clearStandalone = false
            selectMenuTab(req)
        } else if (clearStandalone) {
            clearStandalone = false
            selectMenuTab(null)
        } else if (_binding != null && standaloneTab == null) {
            if (isTv()) {
                val wantedPagerTabs = computeAvailableTvPagerTabs(enabledTabsList, showVideosTab)
                if (pagerTabs != wantedPagerTabs) {
                    bindTvPagerAndHeader(wantedPagerTabs, enabledTabsList, defaultTabKey)
                } else if (shownTabs != enabledTabsList) {
                    val activeKey = pagerTabs.getOrNull(binding.viewPager.currentItem)?.takeIf { it in enabledTabsList } ?: defaultTabKey
                    val targetIdx = pagerTabs.indexOf(activeKey).takeIf { it >= 0 } ?: 0
                    if (binding.viewPager.currentItem != targetIdx) {
                        binding.viewPager.setCurrentItem(targetIdx, false)
                    }
                    syncTvHeaderTabs(enabledTabsList, activeKey)
                }
                com.github.andreyasadchy.xtra.ui.view.TvHeader.refresh(binding.root)
            } else if (shownTabs != enabledTabsList) {
                bindMobileTabs(enabledTabsList, defaultTabKey)
            }
        }
    }

    override fun scrollToTop() {
        binding.appBar.setExpanded(true, true)
        (currentFragment as? Scrollable)?.scrollToTop()
    }

    override fun onDestroyView() {
        tabMediator?.detach()
        tabMediator = null
        super.onDestroyView()
        _binding = null
    }
}
