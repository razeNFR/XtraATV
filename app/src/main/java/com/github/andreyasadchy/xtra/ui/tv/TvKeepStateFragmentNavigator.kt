package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.annotation.Keep
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.Navigator
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import com.github.andreyasadchy.xtra.R

/**
 * TV FragmentNavigator that keeps top-level side-menu root destinations alive in memory
 * via [androidx.fragment.app.FragmentTransaction.hide] / [androidx.fragment.app.FragmentTransaction.show]
 * and [androidx.fragment.app.FragmentTransaction.setMaxLifecycle] instead of destroying and
 * re-inflating their view hierarchies on every category switch, while still adding/removing
 * transient sub-screens (game category pages, streamer channel pages, etc.) normally.
 */
@Keep
@Navigator.Name("fragment")
class TvKeepStateFragmentNavigator(
    private val context: Context,
    private val fragmentManager: FragmentManager,
    private val containerId: Int,
    private val rootDestinationIds: Set<Int>,
) : Navigator<FragmentNavigator.Destination>() {

    private val pendingRemoved = HashSet<androidx.fragment.app.Fragment>()

    override fun createDestination(): FragmentNavigator.Destination =
        FragmentNavigator.Destination(this)

    override fun navigate(
        entries: List<NavBackStackEntry>,
        navOptions: NavOptions?,
        navigatorExtras: Extras?,
    ) {
        if (fragmentManager.isStateSaved) return
        for (entry in entries) {
            navigateSingle(entry, navOptions)
        }
    }

    private fun navigateSingle(entry: NavBackStackEntry, navOptions: NavOptions?) {
        val destination = entry.destination as FragmentNavigator.Destination
        val isRoot = destination.id in rootDestinationIds
        val currentStack = state.backStack.value
        val isSingleTop = navOptions?.shouldLaunchSingleTop() == true &&
            currentStack.lastOrNull()?.destination?.id == destination.id

        val tag = when {
            isRoot -> rootTag(destination.id)
            isSingleTop -> currentStack.last().id
            else -> entry.id
        }

        val ft = fragmentManager.beginTransaction().setReorderingAllowed(true)
        val enterAnim = navOptions?.enterAnim ?: -1
        val exitAnim = navOptions?.exitAnim ?: -1
        val popEnterAnim = navOptions?.popEnterAnim ?: -1
        val popExitAnim = navOptions?.popExitAnim ?: -1
        if (enterAnim != -1 || exitAnim != -1 || popEnterAnim != -1 || popExitAnim != -1) {
            ft.setCustomAnimations(
                enterAnim.coerceAtLeast(0),
                exitAnim.coerceAtLeast(0),
                popEnterAnim.coerceAtLeast(0),
                popExitAnim.coerceAtLeast(0),
            )
        }

        var targetFrag = fragmentManager.findFragmentByTag(tag)?.takeUnless { it in pendingRemoved }
        for (existing in fragmentManager.fragments) {
            if (existing !== targetFrag &&
                existing.id == containerId &&
                existing !is DialogFragment &&
                !existing.isRemoving &&
                !existing.isDetached &&
                existing !in pendingRemoved
            ) {
                ft.hide(existing)
                ft.setMaxLifecycle(existing, Lifecycle.State.STARTED)
            }
        }

        if (targetFrag == null) {
            targetFrag = fragmentManager.fragmentFactory
                .instantiate(context.classLoader, destination.className)
                .apply {
                    arguments = entry.arguments ?: Bundle()
                }
            ft.add(containerId, targetFrag, tag)
        } else {
            if (!isRoot && isSingleTop) {
                targetFrag.arguments = entry.arguments ?: Bundle()
            }
            ft.show(targetFrag)
        }
        ft.setMaxLifecycle(targetFrag, Lifecycle.State.RESUMED)
        ft.setPrimaryNavigationFragment(targetFrag)
        ft.commit()

        if (isSingleTop) {
            state.onLaunchSingleTop(entry)
        } else {
            state.push(entry)
        }
    }

    override fun popBackStack(popUpTo: NavBackStackEntry, savedState: Boolean) {
        if (fragmentManager.isStateSaved) return
        val stack = state.backStack.value
        val popIndex = stack.indexOf(popUpTo)
        if (popIndex < 0) return

        val popped = stack.subList(popIndex, stack.size).toList()
        val nextTop = stack.getOrNull(popIndex - 1)
        val ft = fragmentManager.beginTransaction().setReorderingAllowed(true)
        val removedNow = ArrayList<androidx.fragment.app.Fragment>()
        var hasOps = false

        for (entry in popped) {
            val destId = entry.destination.id
            if (destId !in rootDestinationIds) {
                fragmentManager.findFragmentByTag(entry.id)?.let { frag ->
                    pendingRemoved += frag
                    removedNow += frag
                    ft.remove(frag)
                    hasOps = true
                }
            }
        }

        if (nextTop != null) {
            val nextDest = nextTop.destination as FragmentNavigator.Destination
            val nextIsRoot = nextDest.id in rootDestinationIds
            val nextTag = if (nextIsRoot) rootTag(nextDest.id) else nextTop.id
            var topFrag = fragmentManager.findFragmentByTag(nextTag)?.takeUnless { it in pendingRemoved }
            if (topFrag == null) {
                topFrag = fragmentManager.fragmentFactory
                    .instantiate(context.classLoader, nextDest.className)
                    .apply {
                        arguments = nextTop.arguments ?: Bundle()
                    }
                ft.add(containerId, topFrag, nextTag)
            } else {
                ft.show(topFrag)
            }
            for (existing in fragmentManager.fragments) {
                if (existing !== topFrag &&
                    existing.id == containerId &&
                    existing !is DialogFragment &&
                    !existing.isRemoving &&
                    !existing.isDetached &&
                    existing !in pendingRemoved
                ) {
                    ft.hide(existing)
                    ft.setMaxLifecycle(existing, Lifecycle.State.STARTED)
                }
            }
            ft.setMaxLifecycle(topFrag, Lifecycle.State.RESUMED)
            ft.setPrimaryNavigationFragment(topFrag)
            hasOps = true
        }

        if (removedNow.isNotEmpty()) {
            ft.runOnCommit {
                pendingRemoved.removeAll(removedNow.toSet())
            }
        }
        if (hasOps) {
            ft.commit()
        }
        state.pop(popUpTo, savedState)
    }

    /**
     * Instantiates an unvisited root destination hidden in the background ([Lifecycle.State.STARTED])
     * during idle time so its first opening from the TV side rail is instant (`0 ms` inflation).
     */
    fun prewarmRoot(destId: Int, graph: NavGraph): Boolean {
        if (destId !in rootDestinationIds || fragmentManager.isStateSaved) return false
        val tag = rootTag(destId)
        if (fragmentManager.findFragmentByTag(tag) != null) return false
        val dest = graph.findNode(destId) as? FragmentNavigator.Destination ?: return false
        val frag = fragmentManager.fragmentFactory
            .instantiate(context.classLoader, dest.className)
            .apply {
                arguments = Bundle()
            }
        fragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .add(containerId, frag, tag)
            .hide(frag)
            .setMaxLifecycle(frag, Lifecycle.State.STARTED)
            .commit()
        return true
    }

    companion object {
        const val ROOT_TAG_PREFIX = "tv_root_"
        fun rootTag(destId: Int): String = "$ROOT_TAG_PREFIX$destId"
    }
}

/**
 * TV [NavHostFragment] registering [TvKeepStateFragmentNavigator] before graph inflation or state restore.
 */
@Keep
class TvNavHostFragment : NavHostFragment() {

    var keepStateNavigator: TvKeepStateFragmentNavigator? = null
        private set

    override fun onCreateNavHostController(navHostController: NavHostController) {
        super.onCreateNavHostController(navHostController)
        navHostController.setOnBackPressedDispatcher(requireActivity().onBackPressedDispatcher)
        val containerId = if (id != 0 && id != View.NO_ID) id else R.id.navHostFragment
        val navigator = TvKeepStateFragmentNavigator(
            context = requireContext(),
            fragmentManager = childFragmentManager,
            containerId = containerId,
            rootDestinationIds = TV_ROOT_DESTINATIONS,
        )
        keepStateNavigator = navigator
        navHostController.navigatorProvider.addNavigator("fragment", navigator)
    }

    companion object {
        val TV_ROOT_DESTINATIONS = setOf(
            R.id.searchPagerFragment,
            R.id.tvHomeFragment,
            R.id.rootGamesFragment,
            R.id.rootTopFragment,
            R.id.followPagerFragment,
            R.id.followMediaFragment,
            R.id.savedPagerFragment,
            R.id.savedMediaFragment,
            R.id.tvStatsFragment,
            R.id.tvSettingsFragment,
        )
    }
}
