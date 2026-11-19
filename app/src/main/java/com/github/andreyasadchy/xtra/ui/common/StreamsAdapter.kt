package com.github.andreyasadchy.xtra.ui.common

import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Clock
import kotlin.time.Instant

class StreamsAdapter(
    private val fragment: Fragment,
    private val selectTag: (String) -> Unit,
    private val showGame: Boolean = true,
    /** Loads the list again: the player keeps the other streams and the left / right shortcuts up to date with it. */
    private val queueSource: (() -> androidx.paging.PagingSource<Int, Stream>)? = null,
) : PagingDataAdapter<Stream, StreamsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Stream>() {
        override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            oldItem.viewerCount == newItem.viewerCount &&
                    oldItem.gameName == newItem.gameName &&
                    oldItem.title == newItem.title
    }) {

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(recyclerView.context)) {
            com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.attachToRecyclerView(recyclerView) {
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.PAYLOAD_COSTREAM)
                }
            }
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.attachToRecyclerView(recyclerView) {
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.PAYLOAD_PLAYBACK)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, showGame)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int, payloads: MutableList<Any>) {
        val validPayloads = setOf(
            com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.PAYLOAD_COSTREAM,
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.PAYLOAD_PLAYBACK,
        )
        if (payloads.isNotEmpty() && payloads.all { it in validPayloads }) {
            val item = getItem(position)
            if (com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.PAYLOAD_COSTREAM in payloads) {
                holder.bindUsernameOnly(item)
            }
            if (com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.PAYLOAD_PLAYBACK in payloads) {
                holder.bindPlaybackBadgeOnly(item)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class PagingViewHolder(
        private val binding: FragmentStreamsListItemBinding,
        private val fragment: Fragment,
        private val showGame: Boolean,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bindPlaybackBadgeOnly(item: Stream?) {
            val badgeView = binding.tvPlayingBadge ?: return
            if (item == null || !com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(badgeView.context)) {
                badgeView.visibility = View.GONE
                return
            }
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.bindView(badgeView, item.channelLogin)
        }

        fun bindUsernameOnly(item: Stream?) {
            if (item == null) return
            val context = binding.root.context
            if (item.channelName != null) {
                binding.username.visibility = View.VISIBLE
                var base: CharSequence = if (item.channelLogin != null && !item.channelLogin.equals(item.channelName, true)) {
                    when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                        "0" -> "${item.channelName}(${item.channelLogin})"
                        "1" -> item.channelName
                        else -> item.channelLogin
                    }
                } else {
                    item.channelName
                }
                val isTv = context.resources.getBoolean(com.github.andreyasadchy.xtra.R.bool.isTv)
                if (isTv && com.github.andreyasadchy.xtra.ui.tv.TvFavorites.isFavorite(item.channelLogin)) {
                    base = android.text.SpannableStringBuilder("★ ").apply {
                        setSpan(android.text.style.ForegroundColorSpan(0xFFFACC15.toInt()), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        append(base)
                    }
                }
                if (isTv) {
                    com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.enqueue(context, item)
                    val coCount = com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.getCount(item.channelId, item.channelLogin)
                    base = com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.formatUsernameWithBadge(context, base, coCount)
                }
                binding.username.text = base
            } else {
                binding.username.visibility = View.GONE
            }
        }

        fun bind(item: Stream?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val channelListener: (View) -> Unit = {
                        fragment.findNavController().navigate(
                            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                channelId = item.channelId,
                                channelLogin = item.channelLogin,
                                channelName = item.channelName,
                                channelImage = item.channelImage,
                                streamId = item.id
                            )
                        )
                    }
                    root.setOnClickListener {
                        (fragment.activity as MainActivity).startStream(item, snapshot().items, queueSource)
                    }
                    if (item.channelImage != null) {
                        userImage.visibility = View.VISIBLE
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                data(item.channelImage)
                                if (context.prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
                                    transformations(CircleCropTransformation())
                                }
                                crossfade(true)
                                target(userImage)
                            }.build()
                        )
                        userImage.setOnClickListener(channelListener)
                    } else {
                        userImage.visibility = View.GONE
                    }
                    bindUsernameOnly(item)
                    if (item.channelName != null) {
                        username.setOnClickListener(channelListener)
                    }
                    if (com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(context)) {
                        root.setOnFocusChangeListener { _, hasFocus ->
                            com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(title, hasFocus)
                            com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(gameName, hasFocus)
                        }
                        com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(title, root.hasFocus())
                        com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(gameName, root.hasFocus())
                    }
                    val streamTitle = item.title
                    if (!streamTitle.isNullOrBlank()) {
                        title.visibility = View.VISIBLE
                        title.text = streamTitle.trim()
                    } else {
                        title.visibility = View.GONE
                    }
                    if (showGame && item.gameName != null) {
                        val gameListener: (View) -> Unit = {
                            fragment.findNavController().navigate(
                                if (context.prefs().getBoolean(C.UI_GAME_PAGER, true)) {
                                    GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                        gameId = item.gameId,
                                        gameSlug = item.gameSlug,
                                        gameName = item.gameName
                                    )
                                } else {
                                    GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                        gameId = item.gameId,
                                        gameSlug = item.gameSlug,
                                        gameName = item.gameName
                                    )
                                }
                            )
                        }
                        gameName.visibility = View.VISIBLE
                        gameName.text = item.gameName
                        gameName.setOnClickListener(gameListener)
                    } else {
                        gameName.visibility = View.GONE
                    }
                    if (item.thumbnailURL != null) {
                        thumbnail.visibility = View.VISIBLE
                        //update every 5 minutes
                        val minutes = System.currentTimeMillis() / 60000L
                        val lastMinute = minutes % 10
                        val key = if (lastMinute < 5) minutes - lastMinute else minutes - (lastMinute - 5)
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                // a stream that just started has no thumbnail yet on Twitch: the picture of the channel
                                data(com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.picture(item, item.thumbnail))
                                memoryCacheKeyExtra("minutes", key.toString())
                                diskCachePolicy(CachePolicy.DISABLED)
                                crossfade(true)
                                target(thumbnail)
                            }.build()
                        )
                    } else {
                        thumbnail.visibility = View.GONE
                    }
                    if (item.viewerCount != null) {
                        viewers.visibility = View.VISIBLE
                        val count = item.viewerCount ?: 0
                        viewers.text = context.resources.getQuantityString(
                            R.plurals.viewers,
                            count,
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                        )
                    } else {
                        viewers.visibility = View.GONE
                    }
                    if (context.prefs().getBoolean(C.UI_UPTIME, true) && item.createdAt != null) {
                        val text = item.createdAt?.let {
                            Instant.parseOrNull(it)?.takeIf { time -> time.toEpochMilliseconds() > 0 }?.let { createdAt ->
                                val uptime = Clock.System.now() - createdAt
                                if (uptime.isPositive()) {
                                    DateUtils.formatElapsedTime(uptime.inWholeSeconds)
                                } else null
                            }
                        }
                        if (text != null) {
                            uptime.visibility = View.VISIBLE
                            // TV: the same short badge as the cards of the home ("2 h 39")
                            uptime.text = if (com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(context)) {
                                com.github.andreyasadchy.xtra.ui.tv.TvUptime.format(context, item.createdAt) ?: text
                            } else {
                                context.getString(R.string.uptime, text)
                            }
                        } else {
                            uptime.visibility = View.GONE
                        }
                    } else {
                        uptime.visibility = View.GONE
                    }
                    if (!item.tags.isNullOrEmpty() && context.prefs().getBoolean(C.UI_TAGS, true)) {
                        tagsLayout.removeAllViews()
                        tagsLayout.visibility = View.VISIBLE
                        val tagsFlowLayout = Flow(context).apply {
                            layoutParams = ConstraintLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topToTop = tagsLayout.id
                                bottomToBottom = tagsLayout.id
                                startToStart = tagsLayout.id
                                endToEnd = tagsLayout.id
                            }
                            setWrapMode(Flow.WRAP_CHAIN)
                        }
                        tagsLayout.addView(tagsFlowLayout)
                        val ids = mutableListOf<Int>()
                        for (tag in item.tags) {
                            val text = TextView(context)
                            val id = View.generateViewId()
                            text.id = id
                            ids.add(id)
                            text.text = tag
                            context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                                TextViewCompat.setTextAppearance(text, it.getResourceId(0, 0))
                            }
                            text.setOnClickListener {
                                selectTag(tag)
                            }
                            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, context.resources.displayMetrics).toInt()
                            text.setPadding(padding, 0, padding, 0)
                            tagsLayout.addView(text)
                        }
                        tagsFlowLayout.referencedIds = ids.toIntArray()
                    } else {
                        tagsLayout.visibility = View.GONE
                    }
                    bindPlaybackBadgeOnly(item)
                } else {
                    tvPlayingBadge?.visibility = View.GONE
                }
            }
        }
    }
}