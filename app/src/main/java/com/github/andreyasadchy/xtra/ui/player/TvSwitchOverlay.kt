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

    /** In case the info of the stream never shows (it hides the streamers left and right with it). */
    private const val DURATION = 4500L
    private var shownAt = 0L
    private var pendingHide: Runnable? = null

    private class PillRefs(
        val root: LinearLayout,
        val image: ImageView,
        val nameView: TextView,
        val gameView: TextView,
        var login: String? = null,
    )

    /** The info of the stream goes (after its 2.5 s, back, or the whole HUD): the streamers left and right go with it. */
    fun hide(activity: Activity?, force: Boolean = false) {
        // the player of the previous stream closing just after the switch must not hide the streamers just shown
        if (!force && android.os.SystemClock.elapsedRealtime() - shownAt < 800L) {
            return
        }
        val decor = activity?.window?.decorView as? FrameLayout ?: return
        decor.findViewById<View>(R.id.tvSwitchOverlay)?.let { root ->
            pendingHide?.let { root.removeCallbacks(it) }
            pendingHide = null
            root.animate().cancel()
            root.animate().alpha(0f).setDuration(200).withEndAction {
                (root.parent as? ViewGroup)?.removeView(root)
            }.start()
        }
    }

    fun show(activity: Activity, previous: Stream?, next: Stream?) {
        shownAt = android.os.SystemClock.elapsedRealtime()
        val decor = activity.window?.decorView as? FrameLayout ?: return
        val existing = decor.findViewById<FrameLayout>(R.id.tvSwitchOverlay)
        if (previous == null && next == null) {
            existing?.let {
                pendingHide?.let { r -> it.removeCallbacks(r) }
                decor.removeView(it)
            }
            return
        }
        val density = activity.resources.displayMetrics.density
        val margin = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)

        val root: FrameLayout
        val isReuse = existing != null && existing.parent === decor
        if (isReuse) {
            root = existing!!
            pendingHide?.let { root.removeCallbacks(it) }
            root.animate().cancel()
            if (root.alpha < 1f) {
                root.animate().alpha(1f).setDuration(140).start()
            }
        } else {
            existing?.let { decor.removeView(it) }
            root = FrameLayout(activity).apply {
                id = R.id.tvSwitchOverlay
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                alpha = 0f
            }
            val leftRefs = createPill(activity, density, left = true)
            val rightRefs = createPill(activity, density, left = false)
            root.setTag(R.id.tvSwitchOverlay, Pair(leftRefs, rightRefs))
            root.addView(
                leftRefs.root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL,
                ).apply { marginStart = margin }
            )
            root.addView(
                rightRefs.root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                ).apply { marginEnd = margin }
            )
            decor.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.animate().alpha(1f).setDuration(180).start()
        }

        @Suppress("UNCHECKED_CAST")
        val pair = root.getTag(R.id.tvSwitchOverlay) as? Pair<PillRefs, PillRefs>
        if (pair != null) {
            bindPill(activity, pair.first, previous)
            bindPill(activity, pair.second, next)
        }

        val hideTask = Runnable {
            pendingHide = null
            root.animate().alpha(0f).setDuration(260).withEndAction {
                (root.parent as? ViewGroup)?.removeView(root)
            }.start()
        }
        pendingHide = hideTask
        root.postDelayed(hideTask, DURATION)
    }

    private fun bindPill(activity: Activity, refs: PillRefs, stream: Stream?) {
        if (stream == null) {
            refs.root.visibility = View.GONE
            refs.login = null
            return
        }
        refs.root.visibility = View.VISIBLE
        refs.nameView.text = stream.channelName ?: stream.channelLogin.orEmpty()
        val game = stream.gameName?.takeIf { it.isNotBlank() }
        if (game != null) {
            refs.gameView.text = game
            refs.gameView.visibility = View.VISIBLE
        } else {
            refs.gameView.visibility = View.GONE
        }
        val nextLogin = stream.channelLogin
        if (nextLogin != refs.login || refs.image.drawable == null) {
            refs.login = nextLogin
            activity.imageLoader.enqueue(
                ImageRequest.Builder(activity).apply {
                    data(stream.channelImage)
                    transformations(CircleCropTransformation())
                    crossfade(refs.image.drawable == null)
                    target(refs.image)
                }.build()
            )
        }
    }

    private fun createPill(activity: Activity, density: Float, left: Boolean): PillRefs {
        val size = (44 * density).toInt()
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 40 * density
                setColor(0xCC000000.toInt())
            }
            val padding = (8 * density).toInt()
            if (left) {
                setPadding(padding, padding, padding * 2, padding)
            } else {
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
        val nameView = TextView(activity).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
        }
        val gameView = TextView(activity).apply {
            textSize = 12f
            setTextColor(0xB3FFFFFF.toInt())
            maxLines = 1
            maxWidth = (200 * density).toInt()
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), 0, (4 * density).toInt(), 0)
            addView(nameView)
            addView(gameView)
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
        return PillRefs(pill, image, nameView, gameView)
    }
}
