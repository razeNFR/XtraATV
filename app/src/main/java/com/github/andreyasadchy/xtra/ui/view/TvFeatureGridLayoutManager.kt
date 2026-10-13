package com.github.andreyasadchy.xtra.ui.view

import android.animation.ValueAnimator
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TV grid of streams whose card with a playing preview really grows (16:9): the other cards move to make room,
 * the number of columns stays the same, nothing is covered or cut.
 *
 * - 4 to 6 columns: the card takes 2 columns and 2 rows, the next cards flow around it in their order.
 * - 3 columns: the card is half bigger (2 columns: the cards are already big, none grows). The other cards of its row get narrower
 *   and its row gets taller, the rows under it go down.
 *
 * All the rows have the height of a card of one column, so the place of any card is known without laying out
 * the cards before it. The growth is animated: each frame lays the cards between the normal grid and the grid
 * with the big card.
 */
class TvFeatureGridLayoutManager(
    /** Scroll that brings a focused card to its place (the same as the other lists of the TV). */
    private val onRequestRectangle: (RecyclerView, View, Boolean) -> Boolean,
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {

    var spanCount = 4
        set(value) {
            if (field != value) {
                field = value
                clearFeature()
                requestLayout()
            }
        }

    /** Scroll of the content, from its top. */
    private var scrollY = 0
    /** Height of a row: a card of one column, its margins and decorations. */
    private var rowHeight = 0
    private var measuredForWidth = -1

    private var featured = RecyclerView.NO_POSITION
    private var requested = RecyclerView.NO_POSITION
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var animatorTarget = 0f

    private var pendingPosition = RecyclerView.NO_POSITION
    private var pendingOffset = 0

    private var recyclerView: RecyclerView? = null

    override fun generateDefaultLayoutParams() = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun isAutoMeasureEnabled() = false

    /**
     * The lists are wrap_content in their screens: with cards they take the height they are given (the cards scroll
     * inside), empty they keep only their padding, like the grid of the other lists.
     */
    override fun onMeasure(recycler: RecyclerView.Recycler, state: RecyclerView.State, widthSpec: Int, heightSpec: Int) {
        val width = View.MeasureSpec.getSize(widthSpec)
        val heightSize = View.MeasureSpec.getSize(heightSpec)
        val padding = paddingTop + paddingBottom
        val height = when (View.MeasureSpec.getMode(heightSpec)) {
            View.MeasureSpec.EXACTLY -> heightSize
            View.MeasureSpec.AT_MOST -> if (state.itemCount == 0) min(padding, heightSize) else heightSize
            else -> if (state.itemCount == 0) padding else contentHeight() + padding
        }
        setMeasuredDimension(width, height)
    }

    override fun canScrollVertically() = true

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recyclerView = view
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        animator?.cancel()
        animator = null
        recyclerView = null
    }

    // ---- the big card ----

    /** The grown card (or the one growing / going back to its size), [RecyclerView.NO_POSITION] when none. */
    val featuredPosition get() = featured

    /** Told when the grown card is back to its size: the cards are at their places again. */
    var onCollapsed: (() -> Unit)? = null

    /** The card at [position] grows (its preview plays), [RecyclerView.NO_POSITION]: no card is grown. */
    fun feature(position: Int) {
        // 2 columns: the cards are already big, they do not grow
        requested = if (spanCount >= 3) position else RecyclerView.NO_POSITION
        step()
    }

    private fun clearFeature() {
        animator?.cancel()
        animator = null
        featured = RecyclerView.NO_POSITION
        requested = RecyclerView.NO_POSITION
        progress = 0f
    }

    /** One card grows at a time: the grown card goes back to its size before the next one grows. */
    private fun step() {
        when {
            featured != RecyclerView.NO_POSITION && featured != requested -> animateTo(0f) {
                featured = RecyclerView.NO_POSITION
                onCollapsed?.invoke()
                step()
            }
            featured == RecyclerView.NO_POSITION && requested != RecyclerView.NO_POSITION && requested < itemCount -> {
                featured = requested
                progress = 0f
                animateTo(1f) { bringFeaturedOnScreen() }
            }
            featured != RecyclerView.NO_POSITION -> animateTo(1f) { bringFeaturedOnScreen() }
        }
    }

    private fun animateTo(target: Float, onEnd: () -> Unit) {
        val running = animator
        if (running != null && running.isRunning && animatorTarget == target) {
            return
        }
        running?.removeAllListeners()
        running?.cancel()
        if (progress == target) {
            animator = null
            onEnd()
            return
        }
        animatorTarget = target
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = (320 * kotlin.math.abs(target - progress)).toLong().coerceAtLeast(80L)
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                progress = it.animatedValue as Float
                requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) {
                        progress = target
                        animator = null
                        requestLayout()
                        onEnd()
                    }
                }
            })
            start()
        }
    }

    /** The grown card is shown whole: the list scrolls when it goes past the bottom (or the top) of the screen. */
    private fun bringFeaturedOnScreen() {
        val view = findViewByPosition(featured) ?: return
        val parent = recyclerView ?: return
        val top = getDecoratedTop(view) - (view.layoutParams as RecyclerView.LayoutParams).topMargin
        val bottom = getDecoratedBottom(view) + (view.layoutParams as RecyclerView.LayoutParams).bottomMargin
        val dy = when {
            top < paddingTop -> top - paddingTop
            bottom > height - paddingBottom -> min(bottom - (height - paddingBottom), top - paddingTop)
            else -> 0
        }
        if (dy != 0) {
            parent.smoothScrollBy(0, dy, DecelerateInterpolator(1.6f), 260)
        }
    }

    // ---- places of the cards ----

    private val cellWidth get() = (width - paddingLeft - paddingRight).toFloat() / spanCount

    private val isBlock get() = spanCount >= 4

    private val rowGrowth get() = 1.5f

    /** Place of a card in the normal grid, from the top left of the content. */
    private fun normalRect(position: Int, out: FloatArray) {
        val cell = cellWidth
        out[0] = (position % spanCount) * cell
        out[1] = (position / spanCount).toFloat() * rowHeight
        out[2] = cell
        out[3] = rowHeight.toFloat()
    }

    private fun blockStart(): Int {
        val row = featured / spanCount
        val column = min(featured % spanCount, spanCount - 2)
        return row * spanCount + column
    }

    /** Cell of the [rank]-th card (the grown one left out) in the grid where the grown card takes 2 x 2 cells. */
    private fun blockCell(rank: Int): Int {
        val start = blockStart()
        var cell = rank
        for (blocked in intArrayOf(start, start + 1, start + spanCount, start + spanCount + 1)) {
            if (blocked <= cell) {
                cell++
            }
        }
        return cell
    }

    /** Place of a card in the grid with the grown card. */
    private fun featureRect(position: Int, out: FloatArray) {
        val cell = cellWidth
        val f = featured
        if (isBlock) {
            if (position == f) {
                val start = blockStart()
                out[0] = (start % spanCount) * cell
                out[1] = (start / spanCount).toFloat() * rowHeight
                out[2] = cell * 2
                out[3] = rowHeight * 2f
            } else {
                val x = blockCell(if (position < f) position else position - 1)
                out[0] = (x % spanCount) * cell
                out[1] = (x / spanCount).toFloat() * rowHeight
                out[2] = cell
                out[3] = rowHeight.toFloat()
            }
        } else {
            val row = position / spanCount
            val featuredRow = f / spanCount
            // taller row: the card has the same texts, only its 16:9 thumbnail is taller (known without measuring it: in a
            // layout pass the cards are detached and the grown one could not be measured, its row did not grow)
            val grownHeight = rowHeight + ((cell * rowGrowth - cell) * 9f / 16f).roundToInt()
            out[1] = row.toFloat() * rowHeight + if (row > featuredRow) grownHeight - rowHeight else 0
            if (row == featuredRow) {
                val big = cell * rowGrowth
                val small = (cell * spanCount - big) / (spanCount - 1)
                val column = position % spanCount
                val featuredColumn = f % spanCount
                out[0] = column * small + if (column > featuredColumn) big - small else 0f
                out[2] = if (position == f) big else small
                out[3] = if (position == f) grownHeight.toFloat() else rowHeight.toFloat()
            } else {
                out[0] = (position % spanCount) * cell
                out[2] = cell
                out[3] = rowHeight.toFloat()
            }
        }
    }

    private val normal = FloatArray(4)
    private val grown = FloatArray(4)

    /** Place of a card now: between the normal grid and the grid with the grown card. */
    private fun currentRect(position: Int, out: FloatArray) {
        normalRect(position, out)
        if (featured == RecyclerView.NO_POSITION || progress == 0f || position >= itemCount) {
            return
        }
        featureRect(position, grown)
        for (i in 0..3) {
            out[i] += (grown[i] - out[i]) * progress
        }
    }

    private fun contentHeight(): Int {
        val count = itemCount
        if (count == 0 || rowHeight == 0) {
            return 0
        }
        val rows = (count + spanCount - 1) / spanCount
        val normalHeight = rows * rowHeight
        if (featured == RecyclerView.NO_POSITION || progress == 0f) {
            return normalHeight
        }
        val grownHeight = if (isBlock) {
            val lastCell = max(blockCell(count - 2).takeIf { count > 1 } ?: 0, blockStart() + spanCount + 1)
            (lastCell / spanCount + 1) * rowHeight
        } else {
            normalHeight + ((cellWidth * rowGrowth - cellWidth) * 9f / 16f).roundToInt()
        }
        return (normalHeight + (grownHeight - normalHeight) * progress).roundToInt()
    }

    // ---- layout ----

    private val insets = Rect()

    private fun measure(view: View, width: Int) {
        calculateItemDecorationsForChild(view, insets)
        val params = view.layoutParams as RecyclerView.LayoutParams
        val horizontal = insets.left + insets.right + params.leftMargin + params.rightMargin
        val cardWidth = max(width - horizontal, 0)
        if (view.measuredWidth != cardWidth) {
            // the card changes width: its layouts are measured again from scratch, the 16:9 thumbnail (ratio of a
            // ConstraintLayout) follows the new width instead of keeping the height measured before
            forceLayoutTree(view)
        }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = getChildMeasureSpec(0, View.MeasureSpec.UNSPECIFIED, insets.top + insets.bottom + params.topMargin + params.bottomMargin, params.height, true)
        view.measure(widthSpec, heightSpec)
    }

    private fun forceLayoutTree(view: View) {
        view.forceLayout()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                forceLayoutTree(view.getChildAt(index))
            }
        }
    }

    private fun fullHeight(view: View): Int {
        val params = view.layoutParams as RecyclerView.LayoutParams
        return getDecoratedMeasuredHeight(view) + params.topMargin + params.bottomMargin
    }

    private fun place(view: View, rect: FloatArray) {
        val params = view.layoutParams as RecyclerView.LayoutParams
        measure(view, rect[2].roundToInt())
        val left = paddingLeft + rect[0].roundToInt() + params.leftMargin
        val top = paddingTop + rect[1].roundToInt() - scrollY + params.topMargin
        layoutDecorated(view, left, top, left + getDecoratedMeasuredWidth(view), top + getDecoratedMeasuredHeight(view))
        // the grown card has no focus zoom on top of its growth: it would eat the space around it (or go past the edges)
        if (getPosition(view) == featured && progress > 0f) {
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0) {
            removeAndRecycleAllViews(recycler)
            scrollY = 0
            clearFeature()
            return
        }
        if (featured >= state.itemCount) {
            clearFeature()
        }
        detachAndScrapAttachedViews(recycler)
        if (rowHeight == 0 || measuredForWidth != width) {
            // the height of a card of one column, the same for all the cards of the list
            val view = recycler.getViewForPosition(min(max(pendingPosition, 0), state.itemCount - 1))
            addView(view)
            measure(view, cellWidth.roundToInt())
            rowHeight = fullHeight(view)
            measuredForWidth = width
            detachAndScrapView(view, recycler)
        }
        if (pendingPosition != RecyclerView.NO_POSITION) {
            normalRect(min(pendingPosition, state.itemCount - 1), normal)
            scrollY = normal[1].roundToInt() - pendingOffset
            pendingPosition = RecyclerView.NO_POSITION
            pendingOffset = 0
        }
        scrollY = scrollY.coerceIn(0, maxScroll())
        fill(recycler)
    }

    private fun maxScroll() = max(contentHeight() - (height - paddingTop - paddingBottom), 0)

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (childCount == 0 || dy == 0) {
            return 0
        }
        val target = (scrollY + dy).coerceIn(0, maxScroll())
        val scrolled = target - scrollY
        scrollY = target
        fill(recycler)
        return scrolled
    }

    /** Lays out the cards in the screen, and the screen above and below it so the focus never waits for them. */
    private fun fill(recycler: RecyclerView.Recycler) {
        val count = itemCount
        if (count == 0 || rowHeight == 0) {
            return
        }
        val visible = height - paddingTop - paddingBottom
        val windowTop = scrollY - visible
        val windowBottom = scrollY + visible * 2
        // the cards move by 3 cells at most (2 x 2 card) or one row (taller row): some more are looked at
        val first = max(((windowTop / rowHeight) - 2) * spanCount - 4, 0)
        val last = min(((windowBottom / rowHeight) + 2) * spanCount + 4, count - 1)
        // cards out of the window go back to the pool
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index) ?: continue
            val position = getPosition(child)
            val keep = position in first..last && position < count && intersects(position, windowTop, windowBottom)
            if (!keep && !child.hasFocus()) {
                removeAndRecycleView(child, recycler)
            }
        }
        for (position in first..last) {
            if (!intersects(position, windowTop, windowBottom)) {
                continue
            }
            val view = findViewByPosition(position) ?: recycler.getViewForPosition(position).also { addView(it) }
            currentRect(position, normal)
            place(view, normal)
        }
    }

    private fun intersects(position: Int, top: Int, bottom: Int): Boolean {
        currentRect(position, normal)
        return normal[1] + normal[3] >= top && normal[1] <= bottom
    }

    // ---- scroll to a card ----

    override fun scrollToPosition(position: Int) {
        pendingPosition = position
        pendingOffset = 0
        requestLayout()
    }

    fun scrollToPositionWithOffset(position: Int, offset: Int) {
        pendingPosition = position
        pendingOffset = offset
        requestLayout()
    }

    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
        startSmoothScroll(LinearSmoothScroller(recyclerView.context).apply { targetPosition = position })
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF {
        normalRect(targetPosition, normal)
        return PointF(0f, if (normal[1] < scrollY) -1f else 1f)
    }

    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        return onRequestRectangle(parent, child, immediate)
    }

    fun findFirstVisibleItemPosition(): Int {
        var first = RecyclerView.NO_POSITION
        for (index in 0 until childCount) {
            val child = getChildAt(index) ?: continue
            if (getDecoratedBottom(child) > paddingTop && getDecoratedTop(child) < height - paddingBottom) {
                val position = getPosition(child)
                if (first == RecyclerView.NO_POSITION || position < first) {
                    first = position
                }
            }
        }
        return first
    }

    // ---- edges of the grid, for the D-pad ----

    /** The card is against the left edge: left goes to the side menu. */
    fun isAtStart(position: Int): Boolean {
        currentRect(position, normal)
        return normal[0] < cellWidth / 2
    }

    /** The card is against the right edge: right goes nowhere. */
    fun isAtEnd(position: Int): Boolean {
        currentRect(position, normal)
        return normal[0] + normal[2] > cellWidth * spanCount - cellWidth / 2
    }

    // ---- changes of the list ----

    override fun onItemsChanged(recyclerView: RecyclerView) {
        clearFeature()
    }

    override fun onItemsAdded(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        if (featured != RecyclerView.NO_POSITION && positionStart <= featured) clearFeature()
    }

    override fun onItemsRemoved(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        if (featured != RecyclerView.NO_POSITION && positionStart <= featured) clearFeature()
    }

    override fun onItemsMoved(recyclerView: RecyclerView, from: Int, to: Int, itemCount: Int) {
        if (featured != RecyclerView.NO_POSITION) clearFeature()
    }

    override fun onAdapterChanged(oldAdapter: RecyclerView.Adapter<*>?, newAdapter: RecyclerView.Adapter<*>?) {
        clearFeature()
        scrollY = 0
        removeAllViews()
    }

    // ---- scroll kept when the screen comes back ----

    override fun onSaveInstanceState(): Parcelable {
        val first = findFirstVisibleItemPosition()
        return Bundle().apply {
            putInt(KEY_POSITION, first)
            if (first != RecyclerView.NO_POSITION) {
                normalRect(first, normal)
                putInt(KEY_OFFSET, normal[1].roundToInt() - scrollY)
            }
        }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        val bundle = state as? Bundle ?: return
        val position = bundle.getInt(KEY_POSITION, RecyclerView.NO_POSITION)
        if (position != RecyclerView.NO_POSITION) {
            pendingPosition = position
            pendingOffset = bundle.getInt(KEY_OFFSET, 0)
            requestLayout()
        }
    }

    // ---- scroll bar ----

    override fun computeVerticalScrollOffset(state: RecyclerView.State) = scrollY

    override fun computeVerticalScrollExtent(state: RecyclerView.State) = height - paddingTop - paddingBottom

    override fun computeVerticalScrollRange(state: RecyclerView.State) = contentHeight()

    companion object {
        private const val KEY_POSITION = "position"
        private const val KEY_OFFSET = "offset"
    }
}
