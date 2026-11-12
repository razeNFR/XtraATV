package com.github.andreyasadchy.xtra.ui.common

import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

abstract class PagedListFragment : BaseNetworkFragment(), IntegrityDialog.Listener {

    private var boundRecyclerView: RecyclerView? = null
    private var boundAdapter: PagingDataAdapter<*, *>? = null
    private var lastRefreshedAt = 0L
    private var silentRefresh = false

    fun <T : Any, VH : RecyclerView.ViewHolder> setAdapter(recyclerView: RecyclerView, adapter: PagingDataAdapter<T, VH>) {
        boundRecyclerView = recyclerView
        boundAdapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                adapter.unregisterAdapterDataObserver(this)
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        try {
                            val isTv = recyclerView.resources.getBoolean(com.github.andreyasadchy.xtra.R.bool.isTv)
                            if (positionStart == 0 && (!isTv || (!silentRefresh && !recyclerView.canScrollVertically(-1) && !recyclerView.hasFocus()))) {
                                recyclerView.scrollToPosition(0)
                            }
                        } catch (e: Exception) {

                        }
                    }
                })
            }
        })
        recyclerView.adapter = adapter
    }

    fun shouldShowButton(recyclerView: RecyclerView): Boolean {
        val offset = recyclerView.computeVerticalScrollOffset()
        if (offset < 0) {
            return false
        }
        val extent = recyclerView.computeVerticalScrollExtent()
        val range = recyclerView.computeVerticalScrollRange()
        val percentage = (100f * offset / (range - extent).toFloat())
        return percentage > 3f
    }

    fun <T : Any, VH : RecyclerView.ViewHolder> initializeAdapter(binding: CommonRecyclerViewLayoutBinding, pagingAdapter: PagingDataAdapter<T, VH>, enableSwipeRefresh: Boolean = true, enableScrollTopButton: Boolean = true) {
        boundRecyclerView = binding.recyclerView
        boundAdapter = pagingAdapter
        with(binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    var wasRefreshing = false
                    pagingAdapter.loadStateFlow.collectLatest { loadState ->
                        val isRefreshing = loadState.refresh is LoadState.Loading
                        if (wasRefreshing && !isRefreshing) {
                            if (loadState.refresh is LoadState.NotLoading) {
                                lastRefreshedAt = System.currentTimeMillis()
                            }
                            silentRefresh = false
                        } else if (lastRefreshedAt == 0L && loadState.refresh is LoadState.NotLoading && pagingAdapter.itemCount > 0) {
                            lastRefreshedAt = System.currentTimeMillis()
                        }
                        wasRefreshing = isRefreshing
                        progressBar.isVisible = isRefreshing && pagingAdapter.itemCount == 0
                        if (enableSwipeRefresh) {
                            swipeRefresh.isRefreshing = isRefreshing && pagingAdapter.itemCount != 0 && !silentRefresh
                        }
                        nothingHere.isVisible = !isRefreshing && pagingAdapter.itemCount == 0
                        if ((loadState.refresh as? LoadState.Error ?:
                            loadState.append as? LoadState.Error ?:
                            loadState.prepend as? LoadState.Error)?.error?.message == C.FAILED_INTEGRITY_CHECK
                        ) {
                            (requireActivity() as? MainActivity)?.getNewIntegrityToken("refresh", childFragmentManager)
                        }
                    }
                }
            }
            if (enableSwipeRefresh) {
                swipeRefresh.isEnabled = true
                swipeRefresh.setOnRefreshListener {
                    silentRefresh = false
                    pagingAdapter.refresh()
                }
            }
            if (enableScrollTopButton && requireContext().prefs().getBoolean(C.UI_SCROLL_TOP, true)) {
                recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        super.onScrollStateChanged(recyclerView, newState)
                        scrollTop.isVisible = shouldShowButton(recyclerView)
                    }
                })
                scrollTop.setOnClickListener {
                    (parentFragment as? Scrollable)?.scrollToTop()
                    it.visibility = View.GONE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshIfStale(60_000L)
    }

    /** Refreshes the list silently when stale, without disrupting active scrolling or deep focus on TV. */
    fun refreshIfStale(maxAgeMs: Long = 60_000L) {
        if (!isAdded || this is Searchable) return
        if ((activity as? MainActivity)?.isTvPlayerOverContent() == true) return
        val adapter = boundAdapter ?: return
        if (adapter.itemCount == 0 || lastRefreshedAt == 0L) return
        val now = System.currentTimeMillis()
        if (now - lastRefreshedAt < maxAgeMs) return
        val rv = boundRecyclerView
        val isTv = context?.resources?.getBoolean(com.github.andreyasadchy.xtra.R.bool.isTv) == true
        if (isTv && rv != null) {
            if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
            val focusedChild = rv.focusedChild
            val focusedPos = if (focusedChild != null) rv.getChildAdapterPosition(focusedChild) else RecyclerView.NO_POSITION
            if (focusedPos >= 24 || (focusedPos == RecyclerView.NO_POSITION && rv.computeVerticalScrollOffset() > rv.height)) {
                return
            }
        }
        silentRefresh = isTv
        lastRefreshedAt = now
        adapter.refresh()
    }

    override fun onDestroyView() {
        boundRecyclerView = null
        boundAdapter = null
        silentRefresh = false
        super.onDestroyView()
    }
}