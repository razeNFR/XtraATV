package com.github.andreyasadchy.xtra.ui.player

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.tv.TvAccent

/**
 * TV: after switching streamer with Left / Right, displays compact smoked-glass Edge Dock tabs
 * flush against the left and right edges of the screen (vertically centered) showing the previous
 * and next live streamers in the queue.
 */
object TvSwitchOverlay {

    /** Auto-hide fallback in case HUD peek does not dismiss it earlier. */
    private const val DURATION = 2600L
    private var shownAt = 0L
    private var pendingHide: Runnable? = null
    private val slideInterpolator = FastOutSlowInInterpolator()

    private class PillRefs(
        val root: LinearLayout,
        val accentStripe: View,
        val chevronView: TextView,
        val image: ImageView,
        val nameView: TextView,
        val gameView: TextView,
        val isLeft: Boolean,
        var login: String? = null,
    )

    /** The info of the stream goes (after its 2.5 s, back, or the whole HUD): the edge tabs slide away with it. */
    fun hide(activity: Activity?, force: Boolean = false) {
        // the player of the previous stream closing just after the switch must not hide the streamers just shown
        if (!force && android.os.SystemClock.elapsedRealtime() - shownAt < 800L) {
            return
        }
        val decor = activity?.window?.decorView as? FrameLayout ?: return
        decor.findViewById<FrameLayout>(R.id.tvSwitchOverlay)?.let { root ->
            pendingHide?.let { root.removeCallbacks(it) }
            pendingHide = null
            animateOutAndRemove(root)
        }
    }

    private fun animateOutAndRemove(root: FrameLayout) {
        val density = root.resources.displayMetrics.density
        val slidePx = 14f * density
        @Suppress("UNCHECKED_CAST")
        val pair = root.getTag(R.id.tvSwitchOverlay) as? Pair<PillRefs, PillRefs>
        pair?.first?.root?.animate()?.cancel()
        pair?.second?.root?.animate()?.cancel()
        pair?.first?.root?.animate()?.translationX(-slidePx)?.setDuration(190L)?.setInterpolator(slideInterpolator)?.start()
        pair?.second?.root?.animate()?.translationX(slidePx)?.setDuration(190L)?.setInterpolator(slideInterpolator)?.start()
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(190L).withEndAction {
            (root.parent as? ViewGroup)?.removeView(root)
        }.start()
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
        val slidePx = 16f * density

        val root: FrameLayout
        val isReuse = existing != null && existing.parent === decor
        if (isReuse) {
            root = existing
            pendingHide?.let { root.removeCallbacks(it) }
            root.animate().cancel()
            if (root.alpha < 1f) {
                root.animate().alpha(1f).setDuration(150L).start()
            }
        } else {
            existing?.let { decor.removeView(it) }
            root = FrameLayout(activity).apply {
                id = R.id.tvSwitchOverlay
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                alpha = 0f
            }
            val leftRefs = createEdgeDockTab(activity, density, left = true)
            val rightRefs = createEdgeDockTab(activity, density, left = false)
            leftRefs.root.translationX = -slidePx
            rightRefs.root.translationX = slidePx
            root.setTag(R.id.tvSwitchOverlay, Pair(leftRefs, rightRefs))
            root.addView(
                leftRefs.root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL,
                )
            )
            root.addView(
                rightRefs.root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                )
            )
            decor.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.animate().alpha(1f).setDuration(180L).start()
        }

        @Suppress("UNCHECKED_CAST")
        val pair = root.getTag(R.id.tvSwitchOverlay) as? Pair<PillRefs, PillRefs>
        if (pair != null) {
            val accent = TvAccent.onDark
            bindTab(activity, pair.first, previous, accent)
            bindTab(activity, pair.second, next, accent)
            pair.first.root.animate().cancel()
            pair.second.root.animate().cancel()
            pair.first.root.animate().translationX(0f).setDuration(200L).setInterpolator(slideInterpolator).start()
            pair.second.root.animate().translationX(0f).setDuration(200L).setInterpolator(slideInterpolator).start()
        }

        val hideTask = Runnable {
            pendingHide = null
            animateOutAndRemove(root)
        }
        pendingHide = hideTask
        root.postDelayed(hideTask, DURATION)
    }

    private fun bindTab(activity: Activity, refs: PillRefs, stream: Stream?, accent: Int) {
        if (stream == null) {
            refs.root.visibility = View.GONE
            refs.login = null
            return
        }
        refs.root.visibility = View.VISIBLE
        refs.accentStripe.setBackgroundColor(accent)
        refs.chevronView.setTextColor(ColorUtils.blendARGB(accent, Color.WHITE, 0.38f))
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

    private fun createEdgeDockTab(activity: Activity, density: Float, left: Boolean): PillRefs {
        val radius = 16f * density
        val strokePx = maxOf(1, (1f * density).toInt())
        val avatarSize = (32f * density).toInt()
        val stripeWidth = maxOf(2, (3f * density).toInt())

        val outerBg = GradientDrawable(
            if (left) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(0xD812111A.toInt(), 0xBA181622.toInt()),
        ).apply {
            cornerRadii = if (left) {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            } else {
                floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            }
            setStroke(strokePx, 0x26FFFFFF)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = outerBg
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = 8f * density
        }

        val accentStripe = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(stripeWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(TvAccent.onDark)
        }

        val contentRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val vPad = (7f * density).toInt()
            val edgePad = (6f * density).toInt()
            val innerPad = (12f * density).toInt()
            if (left) {
                setPadding(edgePad, vPad, innerPad, vPad)
            } else {
                setPadding(innerPad, vPad, edgePad, vPad)
            }
        }

        val chevron = TextView(activity).apply {
            text = if (left) "‹" else "›"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(Color.WHITE)
            val hPad = (4f * density).toInt()
            setPadding(hPad, 0, hPad, (1.5f * density).toInt())
        }

        val avatarFrame = FrameLayout(activity).apply {
            val ringPad = maxOf(1, (1f * density).toInt())
            layoutParams = LinearLayout.LayoutParams(avatarSize + ringPad * 2, avatarSize + ringPad * 2)
            setPadding(ringPad, ringPad, ringPad, ringPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(ringPad, 0x38FFFFFF)
            }
        }
        val image = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        avatarFrame.addView(image)

        val maxTextWidth = (116f * density).toInt()
        val nameView = TextView(activity).apply {
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setTextColor(Color.WHITE)
            maxLines = 1
            maxWidth = maxTextWidth
            ellipsize = TextUtils.TruncateAt.END
            gravity = if (left) Gravity.START else Gravity.END
        }
        val gameView = TextView(activity).apply {
            textSize = 10.5f
            includeFontPadding = false
            setTextColor(0xB3FFFFFF.toInt())
            maxLines = 1
            maxWidth = maxTextWidth
            ellipsize = TextUtils.TruncateAt.END
            gravity = if (left) Gravity.START else Gravity.END
            visibility = View.GONE
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (left) Gravity.START else Gravity.END
            val textGap = (8f * density).toInt()
            if (left) {
                setPadding(textGap, 0, (2f * density).toInt(), 0)
            } else {
                setPadding((2f * density).toInt(), 0, textGap, 0)
            }
            addView(nameView)
            addView(gameView)
        }

        if (left) {
            contentRow.addView(chevron)
            contentRow.addView(avatarFrame)
            contentRow.addView(texts)
            container.addView(accentStripe)
            container.addView(contentRow)
        } else {
            contentRow.addView(texts)
            contentRow.addView(avatarFrame)
            contentRow.addView(chevron)
            container.addView(contentRow)
            container.addView(accentStripe)
        }
        return PillRefs(container, accentStripe, chevron, image, nameView, gameView, isLeft = left)
    }
}
