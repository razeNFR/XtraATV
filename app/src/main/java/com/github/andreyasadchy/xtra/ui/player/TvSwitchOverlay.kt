package com.github.andreyasadchy.xtra.ui.player

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream

/**
 * TV: after a switch of streamer with left / right, shows for a few seconds the streamer that left goes to
 * on the left of the screen and the one that right goes to on the right.
 */
object TvSwitchOverlay {

    private const val DURATION = 3500L

    fun show(activity: Activity, previous: Stream?, next: Stream?) {
        val decor = activity.window?.decorView as? FrameLayout ?: return
        decor.findViewById<View>(R.id.tvSwitchOverlay)?.let { decor.removeView(it) }
        if (previous == null && next == null) {
            return
        }
        val density = activity.resources.displayMetrics.density
        val root = FrameLayout(activity).apply {
            id = R.id.tvSwitchOverlay
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val margin = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        previous?.let {
            root.addView(createPill(activity, it, density, left = true), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL
            ).apply { marginStart = margin })
        }
        next?.let {
            root.addView(createPill(activity, it, density, left = false), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = margin })
        }
        root.alpha = 0f
        decor.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.animate().alpha(1f).setDuration(200).start()
        root.postDelayed({
            root.animate().alpha(0f).setDuration(300).withEndAction {
                (root.parent as? ViewGroup)?.removeView(root)
            }.start()
        }, DURATION)
    }

    private fun createPill(activity: Activity, stream: Stream, density: Float, left: Boolean): View {
        val size = (44 * density).toInt()
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 40 * density
                setColor(0xCC000000.toInt())
            }
            val padding = (8 * density).toInt()
            setPadding(padding, padding, (padding * 2), padding)
            if (!left) {
                setPadding(padding * 2, padding, padding, padding)
            }
        }
        val arrow = TextView(activity).apply {
            text = if (left) "‹" else "›"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), (4 * density).toInt())
        }
        val image = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        activity.imageLoader.enqueue(
            ImageRequest.Builder(activity).apply {
                data(stream.channelImage)
                transformations(CircleCropTransformation())
                crossfade(true)
                target(image)
            }.build()
        )
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), 0, (4 * density).toInt(), 0)
            addView(TextView(activity).apply {
                text = stream.channelName ?: stream.channelLogin
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 1
            })
            stream.gameName?.takeIf { it.isNotBlank() }?.let { game ->
                addView(TextView(activity).apply {
                    text = game
                    textSize = 12f
                    setTextColor(0xB3FFFFFF.toInt())
                    maxLines = 1
                    maxWidth = (200 * density).toInt()
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
        }
        if (left) {
            pill.addView(arrow)
            pill.addView(image)
            pill.addView(texts)
        } else {
            pill.addView(texts)
            pill.addView(image)
            pill.addView(arrow)
        }
        return pill
    }
}
