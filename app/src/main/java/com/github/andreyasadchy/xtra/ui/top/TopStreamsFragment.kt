package com.github.andreyasadchy.xtra.ui.top

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentGamesBinding
import com.github.andreyasadchy.xtra.model.ui.GameSort
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.StreamsAdapter
import com.github.andreyasadchy.xtra.ui.common.StreamsCompactAdapter
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog.Companion.RECENT
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog.Companion.SORT_RELEVANCE
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog.Companion.SORT_VIEWERS
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog.Companion.SORT_VIEWERS_ASC
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.top.TopStreamsViewModel.Companion.TopStreamsViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TopStreamsFragment : PagedListFragment(), Scrollable, StreamsSortDialog.OnFilter {

    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!
    private val args: GamePagerFragmentArgs by navArgs()
    private val viewModel: TopStreamsViewModel by viewModels { TopStreamsViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<Stream, out RecyclerView.ViewHolder>

    private var initialFocusDone = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGamesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val activity = requireActivity() as MainActivity
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            if (requireContext().resources.getBoolean(R.bool.isTv) && args.languages?.size == 1) {
                val code = args.languages?.firstOrNull()
                if (!code.isNullOrBlank()) {
                    val values = resources.getStringArray(R.array.gqlUserLanguageValues)
                    val entries = resources.getStringArray(R.array.gqlUserLanguageEntries)
                    val idx = values.indexOfFirst { it.equals(code, ignoreCase = true) }
                    val label = entries.getOrNull(idx) ?: code.uppercase()
                    toolbar.title = getString(R.string.tv_popular_in, label)
                }
            }
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
            if (requireContext().resources.getBoolean(R.bool.isTv)) {
                // TV: the header stays still like on a category page, the lift state changed at each scroll of the list and made it flicker
            } else if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                recyclerViewLayout.recyclerView.let {
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
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                if (activity.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                    val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    recyclerViewLayout.recyclerView.updatePadding(bottom = systemBars.bottom)
                }
                WindowInsetsCompat.CONSUMED
            }
        }
        pagingAdapter = if (requireContext().prefs().getString(C.COMPACT_STREAMS, "disabled") == "all") {
            StreamsCompactAdapter(this, { addTag(it) })
        } else {
            StreamsAdapter(this, { addTag(it) }, queueSource = { viewModel.createSource() })
        }
        setAdapter(binding.recyclerViewLayout.recyclerView, pagingAdapter)
    }

    private fun sortLabelFor(sort: String): String {
        return getString(
            when (sort) {
                SORT_RELEVANCE -> if (TwitchApiHelper.isLoggedIn(requireContext())) R.string.tv_sort_recommended else R.string.viewers_high
                SORT_VIEWERS -> R.string.viewers_high
                SORT_VIEWERS_ASC -> R.string.viewers_low
                RECENT -> R.string.recent
                else -> R.string.viewers_high
            }
        )
    }

    private fun syncSavedSortOnTv() {
        if (!isAdded || _binding == null || !resources.getBoolean(R.bool.isTv) || viewModel.filter.value == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val savedSort = (viewModel.getGameSort("top_streams") ?: viewModel.getGameSort("default"))?.streamSort ?: return@launch
            if (savedSort != viewModel.sort) {
                pagingAdapter.submitData(PagingData.empty())
                viewModel.setFilter(savedSort, viewModel.tags, viewModel.languages)
                viewModel.sortText.value = getString(R.string.sort_by, sortLabelFor(viewModel.sort))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncSavedSortOnTv()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            syncSavedSortOnTv()
        }
    }

    override fun initialize() {
        val isTv = resources.getBoolean(R.bool.isTv)
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                val sortValues = viewModel.getGameSort("top_streams") ?: viewModel.getGameSort("default")
                viewModel.setFilter(
                    sort = sortValues?.streamSort,
                    tags = args.tags ?: if (isTv) null else sortValues?.streamTags?.split(',')?.toTypedArray(),
                    // the language of "Popular" chosen in the Xtra TV settings, the same as the row of the home
                    languages = args.languages ?: popularLanguages() ?: sortValues?.streamLanguages?.split(',')?.toTypedArray(),
                )
                viewModel.sortText.value = getString(R.string.sort_by, sortLabelFor(viewModel.sort))
                viewModel.filtersText.value = if (viewModel.tags.isNotEmpty() || viewModel.languages.isNotEmpty()) {
                    buildString {
                        if (viewModel.tags.isNotEmpty()) {
                            append(
                                resources.getQuantityString(
                                    R.plurals.tags,
                                    viewModel.tags.size,
                                    viewModel.tags.joinToString()
                                )
                            )
                        }
                        if (viewModel.languages.isNotEmpty()) {
                            if (isNotEmpty()) {
                                append(". ")
                            }
                            append(
                                resources.getQuantityString(
                                    R.plurals.languages,
                                    viewModel.languages.size,
                                    viewModel.languages.joinToString()
                                )
                            )
                        }
                    }
                } else null
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        val enableScrollTopButton = !args.tags.isNullOrEmpty() || !args.languages.isNullOrEmpty()
        initializeAdapter(binding.recyclerViewLayout, pagingAdapter, enableScrollTopButton = enableScrollTopButton)
        if (isTv && !args.languages.isNullOrEmpty() && !initialFocusDone) {
            viewLifecycleOwner.lifecycleScope.launch {
                pagingAdapter.loadStateFlow.collectLatest { state ->
                    if (!initialFocusDone && state.refresh is androidx.paging.LoadState.NotLoading && pagingAdapter.itemCount > 0) {
                        initialFocusDone = true
                        binding.recyclerViewLayout.recyclerView.postDelayed({
                            val grid = _binding?.recyclerViewLayout?.recyclerView ?: return@postDelayed
                            if (grid.isShown && grid.focusedChild == null) {
                                com.github.andreyasadchy.xtra.ui.view.TvUi.focusFirstItem(grid)
                            }
                        }, 150L)
                    }
                }
            }
        }
        if (enableScrollTopButton && requireContext().prefs().getBoolean(C.UI_SCROLL_TOP, true)) {
            binding.recyclerViewLayout.scrollTop.setOnClickListener {
                scrollToTop()
                it.visibility = View.GONE
            }
        }
        with(binding) {
            sortBar.root.visibility = View.VISIBLE
            sortBar.root.setOnClickListener {
                StreamsSortDialog.newInstance(
                    sort = viewModel.sort,
                    tags = viewModel.tags,
                    languages = viewModel.languages
                ).show(childFragmentManager, null)
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.sortText.collectLatest {
                        sortBar.sortText.text = it
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.filtersText.collectLatest {
                        if (it != null) {
                            sortBar.filtersText.visibility = View.VISIBLE
                            sortBar.filtersText.text = it
                        } else {
                            sortBar.filtersText.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun addTag(tag: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            pagingAdapter.submitData(PagingData.empty())
            val tags = viewModel.tags.plus(tag).sortedArray()
            viewModel.setFilter(viewModel.sort, tags, viewModel.languages)
            viewModel.filtersText.value = buildString {
                if (viewModel.tags.isNotEmpty()) {
                    append(
                        resources.getQuantityString(
                            R.plurals.tags,
                            viewModel.tags.size,
                            viewModel.tags.joinToString()
                        )
                    )
                }
                if (viewModel.languages.isNotEmpty()) {
                    if (isNotEmpty()) {
                        append(". ")
                    }
                    append(
                        resources.getQuantityString(
                            R.plurals.languages,
                            viewModel.languages.size,
                            viewModel.languages.joinToString()
                        )
                    )
                }
            }
        }
    }

    /** TV: languages of "Popular" from the Xtra TV settings, null when none is chosen. */
    private fun popularLanguages(): Array<String>? {
        if (!resources.getBoolean(R.bool.isTv)) {
            return null
        }
        return requireContext().prefs().getStringSet(C.TV_HOME_LANGUAGES, null)?.filter { it.isNotBlank() }?.ifEmpty { null }?.toTypedArray()
    }

    override fun onChange(sort: String, sortText: CharSequence, tags: Array<String>, languages: Array<String>, changed: Boolean, saveFilters: Boolean, saveSort: Boolean, saveDefault: Boolean) {
        val isTv = resources.getBoolean(R.bool.isTv)
        viewLifecycleOwner.lifecycleScope.launch {
            if (changed) {
                if (isTv && args.languages == null) {
                    // the languages chosen here are the ones of "Popular" everywhere, the row of the home too
                    requireContext().prefs().edit().putStringSet(C.TV_HOME_LANGUAGES, languages.toSet()).apply()
                }
                pagingAdapter.submitData(PagingData.empty())
                viewModel.setFilter(sort, tags, languages)
                viewModel.sortText.value = getString(R.string.sort_by, sortText)
                viewModel.filtersText.value = if (viewModel.tags.isNotEmpty() || viewModel.languages.isNotEmpty()) {
                    buildString {
                        if (viewModel.tags.isNotEmpty()) {
                            append(
                                resources.getQuantityString(
                                    R.plurals.tags,
                                    viewModel.tags.size,
                                    viewModel.tags.joinToString()
                                )
                            )
                        }
                        if (viewModel.languages.isNotEmpty()) {
                            if (isNotEmpty()) {
                                append(". ")
                            }
                            append(
                                resources.getQuantityString(
                                    R.plurals.languages,
                                    viewModel.languages.size,
                                    viewModel.languages.joinToString()
                                )
                            )
                        }
                    }
                } else null
            }
            if (saveFilters && (tags.isNotEmpty() || languages.isNotEmpty())) {
                viewModel.saveFilters(
                    SavedFilter(
                        tags = tags.takeIf { it.isNotEmpty() }?.joinToString(","),
                        languages = languages.takeIf { it.isNotEmpty() }?.joinToString(",")
                    )
                )
            }
            if (saveDefault) {
                val savedTags = if (isTv) null else tags.takeIf { it.isNotEmpty() }?.joinToString(",")
                val savedLangs = if (isTv && args.languages != null) {
                    viewModel.getGameSort("top_streams")?.streamLanguages
                } else {
                    languages.takeIf { it.isNotEmpty() }?.joinToString(",")
                }
                val item = viewModel.getGameSort("top_streams")?.apply {
                    streamSort = sort
                    if (!isTv) streamTags = savedTags
                    streamLanguages = savedLangs
                } ?: GameSort(
                    id = "top_streams",
                    streamSort = sort,
                    streamTags = savedTags,
                    streamLanguages = savedLangs,
                )
                viewModel.saveGameSort(item)
                if (isTv) {
                    val defaultItem = viewModel.getGameSort("default")?.apply {
                        streamSort = sort
                    } ?: GameSort(
                        id = "default",
                        streamSort = sort,
                    )
                    viewModel.saveGameSort(defaultItem)
                }
            }
        }
    }

    override fun deleteSavedSort() {}

    override fun scrollToTop() {
        with(binding) {
            appBar.setExpanded(true, true)
            recyclerViewLayout.recyclerView.scrollToPosition(0)
        }
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onIntegrityTokenLoaded(callback: String?) {
        when (callback) {
            "refresh" -> {
                pagingAdapter.refresh()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}