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

    fun show(activity: MainActivity, streams: List<Stream>, currentChannelLogin: String?) {
        if (streams.isEmpty()) {
            return
        }
        val dialog = AppCompatDialog(activity)
        val density = activity.resources.displayMetrics.density
        val horizontalPadding = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.tv_player_scrim_bottom)
            setPadding(horizontalPadding, (48 * density).toInt(), horizontalPadding, activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical))
        }
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.tv_switch_stream)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setPadding((10 * density).toInt(), 0, 0, (4 * density).toInt())
        })
        val recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        // live previews like on the lists, the stream behind is silenced while a preview with sound plays
        val preview = if (activity.prefs().getBoolean(C.UI_TV_PREVIEW, true)) {
            TvStreamPreview(recyclerView, inPlayer = true) { playing ->
                if (TvPreviewSource.hasSound(activity)) {
                    (activity.playerFragment as? PlayerFragment)?.setDucked(playing)
                }
            }
        } else null
        recyclerView.adapter = Adapter(streams, (250 * density).toInt(), preview) { stream ->
            dialog.dismiss()
            activity.startStream(stream)
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
        dialog.setOnDismissListener {
            preview?.release()
        }
        dialog.show()
    }

    private class Adapter(
        private val streams: List<Stream>,
        private val itemWidth: Int,
        private val preview: TvStreamPreview?,
        private val onClick: (Stream) -> Unit,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {

        class ViewHolder(val binding: FragmentStreamsListItemBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = streams.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = FragmentStreamsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            binding.root.layoutParams = binding.root.layoutParams.apply { width = itemWidth }
            TvCards.prepare(binding.root)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val stream = streams[position]
            val context = holder.itemView.context
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
                        data(stream.thumbnail)
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
