package com.github.andreyasadchy.xtra.ui.following

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.andreyasadchy.xtra.ui.following.channels.FollowedChannelsFragment
import com.github.andreyasadchy.xtra.ui.following.games.FollowedGamesFragment
import com.github.andreyasadchy.xtra.ui.following.streams.FollowedStreamsFragment
import com.github.andreyasadchy.xtra.ui.following.videos.FollowedVideosFragment
import kotlin.math.max

class FollowPagerAdapter(
    fragment: Fragment,
    private val tabs: List<String>,
) : FragmentStateAdapter(fragment) {

    override fun createFragment(position: Int): Fragment {
        return when (tabs.getOrNull(position)) {
            "0" -> FollowedGamesFragment()
            "1" -> FollowedStreamsFragment()
            "2" -> FollowedVideosFragment()
            "3" -> FollowedChannelsFragment()
            else -> FollowedStreamsFragment()
        }
    }

    override fun getItemCount(): Int = max(tabs.size, 1)

    // each page is kept by its tab, not by its place: when a tab is hidden in the settings, the next tab does not get the
    // page of the hidden one back (the videos shown in the channels tab)
    override fun getItemId(position: Int): Long = tabs.getOrNull(position)?.toLongOrNull() ?: position.toLong()

    override fun containsItem(itemId: Long): Boolean = if (tabs.isEmpty()) itemId == 0L else tabs.any { it.toLongOrNull() == itemId }
}
