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
                    oldItem.title == newItem.title &&
                    oldItem.language == newItem.language
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
                if (com.github.andreyasadchy.xtra.ui.tv.TvFavorites.isFavorite(item.channelLogin)) {
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
                    val isTv = com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(context)
                    if (!isTv) {
                        root.setOnLongClickListener {
                            (fragment.activity as? MainActivity)?.let { act ->
                                com.github.andreyasadchy.xtra.ui.mobile.MobileUi.showStreamActions(act, item)
                            }
                            true
                        }
                    }
                    if (item.channelImage != null) {
                        userImage.visibility = View.VISIBLE
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                data(item.channelImage)
                                if (isTv) size(72, 72)
                                if (context.prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
                                    transformations(CircleCropTransformation())
                                }
                                crossfade(!isTv)
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
                    if (isTv) {
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
                                if (isTv) size(480, 270)
                                memoryCacheKeyExtra("minutes", key.toString())
                                if (!isTv) diskCachePolicy(CachePolicy.DISABLED)
                                crossfade(!isTv)
                                target(thumbnail)
                            }.build()
                        )
                    } else {
                        thumbnail.visibility = View.GONE
                    }
                    mobileLiveBadge?.visibility = if (item.thumbnailURL != null) View.VISIBLE else View.GONE
                    val mobileCols = if (!isTv) {
                        if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                            context.prefs().getString(C.PORTRAIT_COLUMN_COUNT, "1")?.toIntOrNull() ?: 1
                        } else {
                            context.prefs().getString(C.LANDSCAPE_COLUMN_COUNT, "2")?.toIntOrNull() ?: 2
                        }
                    } else 1
                    val compactMobileGrid = !isTv && mobileCols >= 2
                    if (item.viewerCount != null) {
                        viewers.visibility = View.VISIBLE
                        val count = item.viewerCount ?: 0
                        val formatted = TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                        viewers.text = if (compactMobileGrid) {
                            formatted
                        } else {
                            context.resources.getQuantityString(R.plurals.viewers, count, formatted)
                        }
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
                            // TV or compact multi-column mobile grid: short badge ("2 h 39") so badges never overlap
                            uptime.text = if (isTv || compactMobileGrid) {
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
                    val showLanguageBadge = context.prefs().getBoolean(
                        if (isTv) C.TV_SHOW_STREAM_LANGUAGE else C.UI_STREAM_LANGUAGE,
                        false,
                    )
                    val langCode = if (showLanguageBadge && item.thumbnailURL != null) item.displayLanguageCode else null
                    if (langCode != null) {
                        languageBadge.visibility = View.VISIBLE
                        languageBadge.text = langCode
                        val flagDrawable = StreamLanguageBadge.createFlagDrawable(context, langCode)
                        languageBadge.compoundDrawablePadding = if (flagDrawable != null) {
                            (5 * context.resources.displayMetrics.density).toInt()
                        } else 0
                        languageBadge.setCompoundDrawablesRelative(flagDrawable, null, null, null)
                    } else {
                        languageBadge.visibility = View.GONE
                        languageBadge.setCompoundDrawablesRelative(null, null, null, null)
                    }
                    val maxMobileTags = when {
                        isTv -> Int.MAX_VALUE
                        mobileCols >= 3 -> 0
                        mobileCols == 2 -> 2
                        else -> 4
                    }
                    if (maxMobileTags > 0 && !item.tags.isNullOrEmpty() && context.prefs().getBoolean(C.UI_TAGS, true)) {
                        tagsLayout.removeAllViews()
                        tagsLayout.visibility = View.VISIBLE
                        val density = context.resources.displayMetrics.density
                        val gap = (4 * density).toInt()
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
                            if (!isTv) {
                                setHorizontalStyle(Flow.CHAIN_PACKED)
                                setHorizontalBias(0f)
                                setHorizontalGap(gap)
                                setVerticalGap(gap)
                            }
                        }
                        tagsLayout.addView(tagsFlowLayout)
                        val ids = mutableListOf<Int>()
                        val hPad = (7 * density).toInt()
                        val vPad = (2 * density).toInt()
                        for (tag in item.tags.take(maxMobileTags)) {
                            val text = TextView(context)
                            val id = View.generateViewId()
                            text.id = id
                            ids.add(id)
                            text.text = tag
                            if (!isTv) {
                                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                text.maxLines = 1
                                text.ellipsize = android.text.TextUtils.TruncateAt.END
                                text.setBackgroundResource(R.drawable.mobile_subtle_chip_bg)
                                text.setPadding(hPad, vPad, hPad, vPad)
                            } else {
                                context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                                    TextViewCompat.setTextAppearance(text, it.getResourceId(0, 0))
                                }
                                val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, context.resources.displayMetrics).toInt()
                                text.setPadding(padding, 0, padding, 0)
                            }
                            text.setOnClickListener {
                                selectTag(tag)
                            }
                            tagsLayout.addView(text)
                        }
                        tagsFlowLayout.referencedIds = ids.toIntArray()
                    } else {
                        tagsLayout.visibility = View.GONE
                    }
                    bindPlaybackBadgeOnly(item)
                    if (!isTv) {
                        com.github.andreyasadchy.xtra.ui.mobile.MobileUi.applyResponsiveMediaCardDensity(
                            cardRoot = root,
                            userImage = userImage,
                            title = title,
                            username = username,
                            gameName = gameName,
                            primaryBadges = listOf(viewers),
                            secondaryBadges = listOf(mobileLiveBadge, uptime),
                        )
                    }
                } else {
                    tvPlayingBadge?.visibility = View.GONE
                }
            }
        }
    }
}