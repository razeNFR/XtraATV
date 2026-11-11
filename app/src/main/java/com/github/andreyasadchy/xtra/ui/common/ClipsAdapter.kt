package com.github.andreyasadchy.xtra.ui.common

import android.content.Intent
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
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
import com.github.andreyasadchy.xtra.databinding.FragmentVideosListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.time.Instant

class ClipsAdapter(
    private val fragment: Fragment,
    private val showDownloadDialog: (Clip) -> Unit,
    private val showGame: Boolean = true,
    private val showChannel: Boolean = true,
) : PagingDataAdapter<Clip, ClipsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Clip>() {
        override fun areItemsTheSame(oldItem: Clip, newItem: Clip): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Clip, newItem: Clip): Boolean =
            oldItem.viewCount == newItem.viewCount &&
                    oldItem.title == newItem.title
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentVideosListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, showGame, showChannel)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentVideosListItemBinding,
        private val fragment: Fragment,
        private val showGame: Boolean,
        private val showChannel: Boolean,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Clip?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val isTv = com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(context)
                    root.setOnClickListener {
                        (fragment.activity as MainActivity).startClip(item, snapshot().items.filterNotNull())
                    }
                    root.setOnLongClickListener {
                        if (isTv) {
                            val activity = fragment.activity as? MainActivity ?: return@setOnLongClickListener true
                            val channelLabel = item.channelName ?: item.channelLogin.orEmpty()
                            val actions = buildList {
                                add(
                                    com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                                        context.getString(R.string.tv_multistream_watch),
                                        R.drawable.tv_ic_play,
                                    ) {
                                        activity.startClip(item, snapshot().items.filterNotNull())
                                    }
                                )
                                if (!item.videoId.isNullOrBlank()) {
                                    add(
                                        com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                                            context.getString(R.string.watch_video),
                                            R.drawable.tv_ic_video_library,
                                        ) {
                                            val offsetMs = item.videoOffsetSeconds?.toLong()?.times(1000L)
                                            activity.startVideo(
                                                com.github.andreyasadchy.xtra.model.ui.Video(
                                                    id = item.videoId,
                                                    channelId = item.channelId,
                                                    channelLogin = item.channelLogin,
                                                    channelName = item.channelName,
                                                    channelImageURL = item.channelImageURL,
                                                    gameId = item.gameId,
                                                    gameSlug = item.gameSlug,
                                                    gameName = item.gameName,
                                                    title = item.title,
                                                    createdAt = item.videoCreatedAt ?: item.createdAt,
                                                    animatedPreviewURL = item.videoAnimatedPreviewURL,
                                                ),
                                                offset = offsetMs,
                                                ignoreSavedPosition = offsetMs != null,
                                            )
                                        }
                                    )
                                }
                                if (showChannel && (!item.channelLogin.isNullOrBlank() || !item.channelId.isNullOrBlank())) {
                                    add(
                                        com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                                            context.getString(R.string.tv_open_channel, channelLabel),
                                            R.drawable.tv_ic_person,
                                        ) {
                                            activity.openTvChannel(
                                                com.github.andreyasadchy.xtra.model.ui.Stream(
                                                    channelId = item.channelId,
                                                    channelLogin = item.channelLogin,
                                                    channelName = item.channelName,
                                                    channelImageURL = item.channelImageURL,
                                                ),
                                                null,
                                            )
                                        }
                                    )
                                }
                                if (showGame && !item.gameName.isNullOrBlank()) {
                                    add(
                                        com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                                            context.getString(R.string.tv_open_category, item.gameName),
                                            R.drawable.tv_ic_gamepad,
                                        ) {
                                            activity.openTvGame(item.gameId, item.gameSlug, item.gameName)
                                        }
                                    )
                                }
                                add(
                                    com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                                        context.getString(R.string.download),
                                        R.drawable.tv_ic_download,
                                    ) {
                                        showDownloadDialog(item)
                                    }
                                )
                            }
                            com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.show(
                                activity,
                                item.title?.trim()?.takeIf { it.isNotEmpty() } ?: channelLabel,
                                actions,
                            )
                        } else {
                            showDownloadDialog(item)
                        }
                        true
                    }
                    fragment.requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(fragment.requireContext()).apply {
                            data(item.thumbnail)
                            diskCachePolicy(CachePolicy.DISABLED)
                            crossfade(true)
                            target(thumbnail)
                        }.build()
                    )
                    if (item.createdAt != null) {
                        val text = Instant.parseOrNull(item.createdAt)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let {
                            TwitchApiHelper.formatDate(context, it)
                        }
                        if (text != null) {
                            date.visibility = View.VISIBLE
                            date.text = text
                        } else {
                            date.visibility = View.GONE
                        }
                    } else {
                        date.visibility = View.GONE
                    }
                    if (item.viewCount != null) {
                        views.visibility = View.VISIBLE
                        val count = item.viewCount
                        views.text = context.resources.getQuantityString(
                            R.plurals.views,
                            count,
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                        )
                    } else {
                        views.visibility = View.GONE
                    }
                    if (item.durationSeconds != null) {
                        duration.visibility = View.VISIBLE
                        duration.text = DateUtils.formatElapsedTime(item.durationSeconds.toLong())
                    } else {
                        duration.visibility = View.GONE
                    }
                    if (showChannel) {
                        val channelListener: (View) -> Unit = {
                            fragment.findNavController().navigate(
                                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                    channelId = item.channelId,
                                    channelLogin = item.channelLogin,
                                    channelName = item.channelName,
                                    channelImage = item.channelImage,
                                )
                            )
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
                        if (item.channelName != null) {
                            username.visibility = View.VISIBLE
                            username.text = if (item.channelLogin != null && !item.channelLogin.equals(item.channelName, true)) {
                                when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                    "0" -> "${item.channelName}(${item.channelLogin})"
                                    "1" -> item.channelName
                                    else -> item.channelLogin
                                }
                            } else {
                                item.channelName
                            }
                            username.setOnClickListener(channelListener)
                        } else {
                            username.visibility = View.GONE
                        }
                    } else {
                        userImage.visibility = View.GONE
                        username.visibility = View.GONE
                    }
                    if (!item.title.isNullOrBlank()) {
                        title.visibility = View.VISIBLE
                        title.text = item.title.trim()
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
                    options.setOnClickListener { it ->
                        if (com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(context)) {
                            // TV: the 3 dots open the panel of the actions, like OK held on the card
                            root.performLongClick()
                            return@setOnClickListener
                        }
                        PopupMenu(context, it).apply {
                            inflate(R.menu.media_item)
                            setOnMenuItemClickListener {
                                when (it.itemId) {
                                    R.id.download -> showDownloadDialog(item)
                                    R.id.share -> {
                                        context.startActivity(Intent.createChooser(Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/${item.channelLogin}/clip/${item.id}")
                                            item.title?.let {
                                                putExtra(Intent.EXTRA_TITLE, it)
                                            }
                                            type = "text/plain"
                                        }, null))
                                    }
                                    else -> menu.close()
                                }
                                true
                            }
                            show()
                        }
                    }
                }
            }
        }
    }
}