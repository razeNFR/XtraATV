package com.github.andreyasadchy.xtra.ui.common

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
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentGamesListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

class GamesAdapter(
    private val fragment: Fragment,
    private val selectTag: (Tag) -> Unit,
) : PagingDataAdapter<Game, GamesAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Game>() {
        override fun areItemsTheSame(oldItem: Game, newItem: Game): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Game, newItem: Game): Boolean =
            oldItem.viewerCount == newItem.viewerCount
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentGamesListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentGamesListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Game?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val openCategory = {
                        fragment.findNavController().navigate(
                            if (context.prefs().getBoolean(C.UI_GAME_PAGER, true)) {
                                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = item.id,
                                    gameSlug = item.slug,
                                    gameName = item.name,
                                    boxArt = item.boxArt
                                )
                            } else {
                                GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                    gameId = item.id,
                                    gameSlug = item.slug,
                                    gameName = item.name,
                                    boxArt = item.boxArt
                                )
                            }
                        )
                    }
                    root.setOnClickListener { openCategory() }
                    val isTv = com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(context)
                    if (isTv) {
                        root.setOnLongClickListener {
                            val pinned = com.github.andreyasadchy.xtra.ui.tv.home.TvHomeRows.isCategoryPinned(context, item.id)
                            val actions = buildList {
                                add(
                                    com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                                        context.getString(R.string.tv_open_this_category),
                                        R.drawable.tv_ic_gamepad,
                                    ) {
                                        openCategory()
                                    }
                                )
                                if (!item.id.isNullOrBlank()) {
                                    add(
                                        com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.Action(
                                            context.getString(if (pinned) R.string.tv_home_unpin_category else R.string.tv_home_pin_category),
                                            if (pinned) R.drawable.tv_ic_delete else R.drawable.tv_ic_pin,
                                        ) {
                                            val nowPinned = com.github.andreyasadchy.xtra.ui.tv.home.TvHomeRows.togglePinCategory(context, item)
                                            android.widget.Toast.makeText(
                                                context,
                                                context.getString(
                                                    if (nowPinned) R.string.tv_home_category_pinned else R.string.tv_home_category_unpinned,
                                                    item.name ?: "",
                                                ),
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    )
                                }
                            }
                            com.github.andreyasadchy.xtra.ui.tv.TvActionPanel.show(context, item.name ?: "", actions)
                            true
                        }
                    } else {
                        root.setOnLongClickListener(null)
                    }
                    if (item.boxArt != null) {
                        gameImage.visibility = View.VISIBLE
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                data(item.boxArt)
                                crossfade(!isTv)
                                target(gameImage)
                            }.build()
                        )
                        // TV: the box art blurred behind the texts of the wide card (cached in Coil to avoid live GPU RenderEffect lag)
                        gameBackground?.let { background ->
                            fragment.requireContext().imageLoader.enqueue(
                                ImageRequest.Builder(fragment.requireContext()).apply {
                                    data(item.boxArt)
                                    crossfade(false)
                                    transformations(com.github.andreyasadchy.xtra.ui.view.TvCards.BlurTransformation)
                                    target(background)
                                }.build()
                            )
                        }
                    } else {
                        gameImage.visibility = View.GONE
                        gameBackground?.setImageDrawable(null)
                    }
                    if (isTv) {
                        root.setOnFocusChangeListener { _, hasFocus -> com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(gameName, hasFocus) }
                        com.github.andreyasadchy.xtra.ui.view.TvMarquee.update(gameName, root.hasFocus())
                    }
                    if (item.name != null) {
                        gameName.visibility = View.VISIBLE
                        gameName.text = item.name
                    } else {
                        gameName.visibility = View.GONE
                    }
                    if (item.viewerCount != null) {
                        viewers.visibility = View.VISIBLE
                        val count = item.viewerCount ?: 0
                        viewers.text = if (gameBackground != null) {
                            // TV: the number next to the eye, the word does not fit
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                        } else {
                            context.resources.getQuantityString(
                                R.plurals.viewers,
                                count,
                                TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                            )
                        }
                    } else {
                        viewers.visibility = View.GONE
                    }
                    if (item.broadcasterCount != null && context.prefs().getBoolean(C.UI_BROADCASTERS_COUNT, true)) {
                        broadcastersCount.visibility = View.VISIBLE
                        val count = item.broadcasterCount ?: 0
                        broadcastersCount.text = context.resources.getQuantityString(
                            R.plurals.broadcasters,
                            count,
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                        )
                    } else {
                        broadcastersCount.visibility = View.GONE
                    }
                    if (!isTv && !item.tags.isNullOrEmpty() && context.prefs().getBoolean(C.UI_TAGS, true)) {
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
                        for (tag in item.tags!!) {
                            val text = TextView(context)
                            val id = View.generateViewId()
                            text.id = id
                            ids.add(id)
                            text.text = tag.name
                            context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                                TextViewCompat.setTextAppearance(text, it.getResourceId(0, 0))
                            }
                            if (tag.id != null) {
                                text.setOnClickListener {
                                    selectTag(tag)
                                }
                            }
                            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, context.resources.displayMetrics).toInt()
                            text.setPadding(padding, 0, padding, 0)
                            tagsLayout.addView(text)
                        }
                        tagsFlowLayout.referencedIds = ids.toIntArray()
                    } else {
                        tagsLayout.visibility = View.GONE
                    }
                }
            }
        }
    }
}
