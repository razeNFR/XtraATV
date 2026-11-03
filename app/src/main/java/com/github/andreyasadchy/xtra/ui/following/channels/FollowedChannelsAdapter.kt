package com.github.andreyasadchy.xtra.ui.following.channels

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentFollowedChannelsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Instant

class FollowedChannelsAdapter(
    private val fragment: Fragment,
) : PagingDataAdapter<User, FollowedChannelsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: User, newItem: User): Boolean = true
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentFollowedChannelsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentFollowedChannelsListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        /**
         * TV: a big avatar, the live channels with a red ring, LIVE and their category with the viewers, the others
         * with their last live in relative time ("2 hours ago") and the month they were followed. A favorite has a
         * star, a channel followed only on this TV a small TV.
         */
        private fun bindTv(item: User) {
            with(binding) {
                val context = fragment.requireContext()
                val live = item.liveViewers != null
                userRing?.visibility = if (live) View.VISIBLE else View.INVISIBLE
                liveBadge?.visibility = if (live) View.VISIBLE else View.GONE
                favoriteStar?.visibility = if (com.github.andreyasadchy.xtra.ui.tv.TvFavorites.isFavorite(item.login)) View.VISIBLE else View.GONE
                localIcon?.visibility = if (item.localFollow && !item.accountFollow) View.VISIBLE else View.GONE
                accountText.visibility = View.GONE
                localText.visibility = View.GONE
                // the display name only, the login in brackets does not fit under the avatar
                item.name?.let { username.text = it }
                if (live) {
                    userStream.visibility = View.VISIBLE
                    userStream.setTextColor(0xFFFF8A88.toInt())
                    val viewers = TwitchApiHelper.formatCount(item.liveViewers ?: 0, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                    userStream.text = listOfNotNull(item.liveGame?.takeIf { it.isNotBlank() }, viewers).joinToString(" · ")
                    userFollowed.visibility = View.GONE
                } else {
                    userStream.setTextColor(0xD9FFFFFF.toInt())
                    val last = item.lastBroadcast?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } }
                    if (last != null) {
                        userStream.visibility = View.VISIBLE
                        userStream.text = android.text.format.DateUtils.getRelativeTimeSpanString(
                            last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                        ).toString().replaceFirstChar { it.uppercase() }
                    } else {
                        userStream.visibility = View.GONE
                    }
                    val followed = item.followedAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } }
                    if (followed != null) {
                        val locale = java.util.Locale.getDefault()
                        val month = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMyyyy"), locale).format(java.util.Date(followed))
                        userFollowed.visibility = View.VISIBLE
                        userFollowed.text = context.getString(R.string.tv_followed_since, month)
                    } else {
                        userFollowed.visibility = View.GONE
                    }
                }
            }
        }

        fun bind(item: User?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    root.setOnClickListener {
                        fragment.findNavController().navigate(
                            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                channelId = item.id,
                                channelLogin = item.login,
                                channelName = item.name,
                                channelImage = item.profileImage,
                            )
                        )
                    }
                    if (item.profileImage != null) {
                        userImage.visibility = View.VISIBLE
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                data(item.profileImage)
                                if (context.prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
                                    transformations(CircleCropTransformation())
                                }
                                crossfade(true)
                                target(userImage)
                            }.build()
                        )
                    } else {
                        userImage.visibility = View.GONE
                    }
                    if (item.name != null) {
                        username.visibility = View.VISIBLE
                        username.text = if (item.login != null && !item.login.equals(item.name, true)) {
                            when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                "0" -> "${item.name}(${item.login})"
                                "1" -> item.name
                                else -> item.login
                            }
                        } else {
                            item.name
                        }
                    } else {
                        username.visibility = View.GONE
                    }
                    if (item.lastBroadcast != null) {
                        val text = item.lastBroadcast?.let {
                            Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let { time ->
                                TwitchApiHelper.formatDate(context, time)
                            }
                        }
                        if (text != null) {
                            userStream.visibility = View.VISIBLE
                            userStream.text = context.getString(R.string.last_broadcast_date, text)
                        } else {
                            userStream.visibility = View.GONE
                        }
                    } else {
                        userStream.visibility = View.GONE
                    }
                    if (item.followedAt != null) {
                        val text = item.followedAt?.let {
                            Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let { time ->
                                TwitchApiHelper.formatDate(context, time)
                            }
                        }
                        if (text != null) {
                            userFollowed.visibility = View.VISIBLE
                            userFollowed.text = context.getString(R.string.followed_at, text)
                        } else {
                            userFollowed.visibility = View.GONE
                        }
                    } else {
                        userFollowed.visibility = View.GONE
                    }
                    if (item.accountFollow) {
                        accountText.visibility = View.VISIBLE
                    } else {
                        accountText.visibility = View.GONE
                    }
                    if (item.localFollow) {
                        localText.visibility = View.VISIBLE
                    } else {
                        localText.visibility = View.GONE
                    }
                    if (liveBadge != null) {
                        bindTv(item)
                    }
                }
            }
        }
    }
}