package com.github.andreyasadchy.xtra.ui.player

import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource
import com.github.andreyasadchy.xtra.ui.view.TvStreamPreview
import com.github.andreyasadchy.xtra.ui.view.TvCards
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

/**
 * TV: row of the streams from the list the current stream was opened from, shown over the player
 * to switch to another stream without leaving it.
 */
object TvStreamSwitcher {

    /** Puts a newer list in the row while it is shown, null when it is not shown. */
    private var updater: ((List<Stream>) -> Unit)? = null

    /** The list of the other streams was loaded again: the row shown follows it, the focus stays on the same streamer. */
    fun update(streams: List<Stream>) {
        updater?.invoke(streams)
    }

    /**
     * Selection mode of the row (multi-stream): OK checks or unchecks a stream (green badge) up to [max],
     * the "Validate" button gives the checked ones.
     */
    class Selection(
        val title: String,
        val preselected: Set<String>,
        val max: Int,
        val onValidate: (List<Stream>) -> Unit,
    )

    fun show(activity: MainActivity, streams: List<Stream>, currentChannelLogin: String?, selection: Selection? = null) {
        if (streams.isEmpty()) {
            return
        }
        val checked = selection?.preselected?.toMutableSet()
        val dialog = AppCompatDialog(activity)
        val density = activity.resources.displayMetrics.density
        val horizontalPadding = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.tv_player_scrim_bottom)
            // no side padding here: the row goes to the edges of the screen, the cards are not cut while it scrolls
            setPadding(0, (48 * density).toInt(), 0, activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPadding + (10 * density).toInt(), 0, horizontalPadding, (4 * density).toInt())
        }
        header.addView(TextView(activity).apply {
            text = selection?.title ?: activity.getString(R.string.tv_switch_stream)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            // readable over a bright video
            setShadowLayer(8 * density, 0f, 2 * density, 0xE6000000.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // selection: "Validate (n)" over the row, up from the cards
        val validate = if (selection != null && checked != null) {
            TextView(activity).apply {
                setTextColor(activity.getColorStateList(R.color.tv_pill_text))
                setBackgroundResource(R.drawable.tv_validate_background)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                elevation = 6 * density
                isFocusable = true
                val padding = (10 * density).toInt()
                setPadding(padding * 2, padding, padding * 2, padding)
                val icon = activity.getDrawable(R.drawable.tv_ic_check)?.mutate()?.apply {
                    setTintList(activity.getColorStateList(R.color.tv_pill_text))
                    setBounds(0, 0, (20 * density).toInt(), (20 * density).toInt())
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = (8 * density).toInt()
            }.also { header.addView(it) }
        } else null
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            clipChildren = false
            setPadding(horizontalPadding, (8 * density).toInt(), horizontalPadding, (8 * density).toInt())
        }
        // live previews like on the lists, the stream behind is silenced while a preview with sound plays
        val preview = if (activity.prefs().getBoolean(C.UI_TV_PREVIEW, true)) {
            TvStreamPreview(recyclerView, inPlayer = true) { playing ->
                if (TvPreviewSource.hasSound(activity)) {
                    (activity.playerFragment as? PlayerFragment)?.setDucked(playing)
                }
            }
        } else null
        lateinit var adapter: Adapter
        val updateValidate = {
            validate?.text = activity.getString(R.string.tv_multistream_validate, checked?.size ?: 0)
            // nothing checked: the button is dimmed
            validate?.alpha = if (checked.isNullOrEmpty()) 0.6f else 1f
        }
        updateValidate()
        adapter = Adapter(streams, (250 * density).toInt(), preview, checked) { stream ->
            if (selection != null && checked != null) {
                val login = stream.channelLogin ?: return@Adapter
                when {
                    login in checked -> checked.remove(login)
                    selection.max == 1 -> {
                        // one stream to pick (change or add a stream): picked at once
                        dialog.dismiss()
                        selection.onValidate(listOf(stream))
                        return@Adapter
                    }
                    checked.size >= selection.max -> {
                        android.widget.Toast.makeText(activity, activity.getString(R.string.tv_multistream_max, selection.max), android.widget.Toast.LENGTH_SHORT).show()
                        return@Adapter
                    }
                    else -> checked.add(login)
                }
                adapter.streams.indexOf(stream).takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
                updateValidate()
            } else {
                dialog.dismiss()
                activity.startStream(stream)
            }
        }
        recyclerView.adapter = adapter
        validate?.setOnClickListener {
            val logins = checked ?: return@setOnClickListener
            if (logins.isEmpty()) {
                return@setOnClickListener
            }
            dialog.dismiss()
            // in the order of the row, the current stream first
            val all = (adapter.streams + streams).distinctBy { it.channelLogin }
            selection?.onValidate(all.filter { it.channelLogin in logins }.sortedBy { if (it.channelLogin == currentChannelLogin) 0 else 1 })
        }
        root.addView(recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0f)
        }
        val currentIndex = streams.indexOfFirst { it.channelLogin != null && it.channelLogin == currentChannelLogin }.coerceAtLeast(0)
        recyclerView.scrollToPosition(currentIndex)
        dialog.setOnShowListener {
            recyclerView.post {
                val view = recyclerView.layoutManager?.findViewByPosition(currentIndex)
                if (view == null || !view.requestFocus()) {
                    recyclerView.getChildAt(0)?.requestFocus()
                }
            }
        }
        val update: (List<Stream>) -> Unit = update@{ list ->
            // only when streamers came or went or moved, not for new viewer counts: the row does not move for nothing
            if (!dialog.isShowing || list.isEmpty() || list.map { it.channelLogin } == adapter.streams.map { it.channelLogin }) {
                return@update
            }
            val focusedLogin = recyclerView.focusedChild?.let { recyclerView.getChildAdapterPosition(it) }
                ?.let { adapter.streams.getOrNull(it)?.channelLogin }
            adapter.streams = list
            adapter.notifyDataSetChanged()
            val index = list.indexOfFirst { it.channelLogin != null && it.channelLogin == focusedLogin }
            if (index >= 0) {
                recyclerView.scrollToPosition(index)
                recyclerView.post { recyclerView.layoutManager?.findViewByPosition(index)?.requestFocus() }
            }
        }
        updater = update
        dialog.setOnDismissListener {
            if (updater === update) {
                updater = null
            }
            preview?.release()
        }
        dialog.show()
    }

    private class Adapter(
        var streams: List<Stream>,
        private val itemWidth: Int,
        private val preview: TvStreamPreview?,
        /** Selection mode: the logins checked, they get a green badge. */
        private val checked: Set<String>?,
        private val onClick: (Stream) -> Unit,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {

        class ViewHolder(val binding: FragmentStreamsListItemBinding, val badge: android.widget.ImageView) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = streams.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            binding.root.layoutParams = binding.root.layoutParams.apply { width = itemWidth }
            TvCards.prepare(binding.root)
            val density = parent.resources.displayMetrics.density
            // green check of a selected stream, at the top right of the card
            val badge = android.widget.ImageView(parent.context).apply {
                setImageResource(R.drawable.tv_ic_check)
                setColorFilter(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFF22C55E.toInt())
                    setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
                }
                val padding = (5 * density).toInt()
                setPadding(padding, padding, padding, padding)
                visibility = View.GONE
                elevation = 8 * density
            }
            (binding.root as? android.widget.FrameLayout)?.addView(badge, android.widget.FrameLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt(), Gravity.TOP or Gravity.END).apply {
                val margin = (8 * density).toInt()
                setMargins(margin, margin, margin, margin)
            })
            return ViewHolder(binding, badge)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val stream = streams[position]
            val context = holder.itemView.context
            holder.badge.visibility = if (checked != null && stream.channelLogin in checked) View.VISIBLE else View.GONE
            with(holder.binding) {
                root.setOnClickListener { onClick(stream) }
                root.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        preview?.schedule(view, stream.channelLogin)
                    } else if (preview?.isPreviewing(view) == true) {
                        preview.stop()
                    }
                }
                thumbnail.visibility = View.VISIBLE
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context).apply {
                        data(com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.picture(stream, stream.thumbnail))
                        crossfade(true)
                        target(thumbnail)
                    }.build()
                )
                if (stream.channelImage != null) {
                    userImage.visibility = View.VISIBLE
                    context.imageLoader.enqueue(
                        ImageRequest.Builder(context).apply {
                            data(stream.channelImage)
                            transformations(CircleCropTransformation())
                            crossfade(true)
                            target(userImage)
                        }.build()
                    )
                } else {
                    userImage.visibility = View.GONE
                }
                title.visibility = if (stream.title.isNullOrBlank()) View.GONE else View.VISIBLE
                title.text = stream.title?.trim()
                username.visibility = View.VISIBLE
                username.text = stream.channelName ?: stream.channelLogin
                gameName.visibility = if (stream.gameName.isNullOrBlank()) View.GONE else View.VISIBLE
                gameName.text = stream.gameName
                val count = stream.viewerCount
                if (count != null) {
                    viewers.visibility = View.VISIBLE
                    viewers.text = context.resources.getQuantityString(
                        R.plurals.viewers,
                        count,
                        TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                    )
                } else {
                    viewers.visibility = View.GONE
                }
                uptime.visibility = View.GONE
                tagsLayout.visibility = View.GONE
            }
        }
    }
}
