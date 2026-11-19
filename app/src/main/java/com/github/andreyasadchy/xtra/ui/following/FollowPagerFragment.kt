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

    /** Keys of the tabs shown ("0" categories, "1" live, "2" videos, "3" channels). */
    private var shownTabs: List<String> = emptyList()
    private var enabledTabsList: List<String> = emptyList()
    private var defaultTabKey: String = "1"
    private var tabMediator: TabLayoutMediator? = null

    private var pendingTab = -1

    /** A tab shown alone (asked from the side menu, not among the tabs of the screen), kept when the screen comes back. */
    private var standaloneTab: String? = null

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

    /** TV side menu: goes to a tab of the screen already open, false when it is not shown there. */
    fun showTab(key: String): Boolean {
        val index = shownTabs.indexOf(key)
        if (index >= 0 && _binding != null) {
            binding.viewPager.setCurrentItem(index, false)
            return true
        }
        return false
    }

    /**
     * Switches between full Following tabs (`key == null`) and any sub-entry (`"0"`, `"2"`, `"3"`)
     * directly in place without destroying or recreating [FollowPagerFragment].
     */
    fun selectMenuTab(key: String?): Boolean {
        if (_binding == null || !isAdded) return false
        val showVideosTab = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank()
        if (key == null) {
            if (standaloneTab != null || shownTabs != enabledTabsList) {
                standaloneTab = null
                bindTabs(enabledTabsList, defaultTabKey)
            } else {
                val defaultIdx = shownTabs.indexOf(defaultTabKey).takeIf { it >= 0 } ?: 0
                if (binding.viewPager.currentItem != defaultIdx) {
                    binding.viewPager.setCurrentItem(defaultIdx, false)
                }
            }
        } else {
            if (key == "2" && !showVideosTab) return false
            if (key in enabledTabsList) {
                if (standaloneTab != null || shownTabs != enabledTabsList) {
                    standaloneTab = null
                    bindTabs(enabledTabsList, key)
                } else {
                    val idx = shownTabs.indexOf(key)
                    if (idx >= 0 && binding.viewPager.currentItem != idx) {
                        binding.viewPager.setCurrentItem(idx, false)
                    }
                }
            } else if (standaloneTab != key || shownTabs != listOf(key)) {
                standaloneTab = key
                bindTabs(listOf(key), key)
            }
        }
        binding.root.postDelayed({
            if (_binding != null && isResumed) {
                (currentFragment as? com.github.andreyasadchy.xtra.ui.common.PagedListFragment)?.refreshIfStale(20_000L)
            }
        }, 160L)
        com.github.andreyasadchy.xtra.ui.view.TvHeader.refresh(binding.root)
        return true
    }

    private fun bindTabs(tabs: List<String>, targetKey: String?) {
        val b = _binding ?: return
        tabMediator?.detach()
        tabMediator = null
        shownTabs = tabs
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
        b.viewPager.offscreenPageLimit = if (requireContext().resources.getBoolean(R.bool.isTv)) {
            1
        } else {
            adapter.itemCount
        }
        if (targetKey != null) {
            val idx = tabs.indexOf(targetKey).takeIf { it >= 0 }
                ?: tabs.indexOf("1").takeIf { it >= 0 }
                ?: 0
            b.viewPager.setCurrentItem(idx, false)
        }
        tabMediator = TabLayoutMediator(b.tabLayout, b.viewPager) { tab, position ->
            tab.text = when (tabs.getOrNull(position)) {
                "0" -> getString(R.string.games)
                "1" -> getString(R.string.live)
                "2" -> getString(R.string.videos)
                "3" -> getString(R.string.channels)
                else -> getString(R.string.live)
            }
        }.also { it.attach() }
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
                if (enabled && (key != "2" || showVideosTab)) {
                    key
                } else {
                    null
                }
            }
            enabledTabsList = enabledTabs
            defaultTabKey = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
            val wasClearStandalone = clearStandalone
            if (clearStandalone) {
                clearStandalone = false
                standaloneTab = null
            }
            // TV: opened from an entry of the side menu (videos, channels, categories of the followed channels); a tab
            // hidden in the settings is shown alone, the settings stay as they are
            // a tab asked from the side menu that is not among the tabs of the screen (Followed channels...) is the
            // only page of the screen: it stays so when the screen comes back (back from a channel opened from it)
            val requested = (requestedTab?.also { requestedTab = null } ?: standaloneTab)?.takeIf { it != "2" || showVideosTab }
            standaloneTab = requested?.takeIf { it !in enabledTabs }
            val tabs = if (requested != null && requested !in enabledTabs) listOf(requested) else enabledTabs
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    viewPager.doOnLayout {
                        childFragmentManager.findFragmentByTag("f${binding.viewPager.adapter?.getItemId(position) ?: position}")?.let { fragment ->
                            if (requireContext().resources.getBoolean(R.bool.isTv)) {
                                // TV: the header stays still like on a category page, the lift state changed at each scroll of the list and made it flicker
                            } else if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                                fragment.view?.findViewById<RecyclerView>(R.id.recyclerView)?.let {
                                    appBar.setLiftOnScrollTargetView(it)
                                    it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                            super.onScrolled(recyclerView, dx, dy)
                                            appBar.isLifted = recyclerView.canScrollVertically(-1)
                                        }
                                    })
                                    it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                        appBar.isLifted = it.canScrollVertically(-1)
                                    }
                                }
                            } else {
                                appBar.setLiftable(false)
                                appBar.background = null
                            }
                            if (fragment is Sortable) {
                                fragment.setupSortBar(sortBar)
                            } else {
                                sortBar.root.visibility = View.GONE
                            }
                        }
                    }
                }
            })
            viewPager.reduceDragSensitivity()
            val targetKey = requested ?: if (firstLaunch || wasClearStandalone) defaultTabKey else null
            bindTabs(tabs, targetKey)
            if (requested != null) {
                pendingTab = tabs.indexOf(requested)
                firstLaunch = false
            } else if (wasClearStandalone) {
                pendingTab = tabs.indexOf(defaultTabKey).coerceAtLeast(0)
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
        } else if (_binding != null && standaloneTab == null && shownTabs != enabledTabsList) {
            bindTabs(enabledTabsList, defaultTabKey)
            com.github.andreyasadchy.xtra.ui.view.TvHeader.refresh(binding.root)
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
