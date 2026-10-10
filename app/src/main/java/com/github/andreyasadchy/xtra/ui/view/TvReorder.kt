package com.github.andreyasadchy.xtra.ui.view

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.google.android.material.color.MaterialColors

/**
 * TV: reordering lists by dragging is impossible with a remote, so every row with a drag handle
 * gets "move up" and "move down" buttons next to it, reusing the drag callback of the list.
 */
object TvReorder {

    fun install(recyclerView: RecyclerView, callback: ItemTouchHelper.Callback) {
        if (!recyclerView.resources.getBoolean(R.bool.isTv)) {
            return
        }
        recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val handle = view.findViewById<View>(R.id.image) ?: return
                val parent = handle.parent as? LinearLayout ?: return
                if (view.findViewById<View>(R.id.tvMoveUp) == null) {
                    val index = parent.indexOfChild(handle)
                    val size = (48 * view.resources.displayMetrics.density).toInt()
                    val color = MaterialColors.getColorStateListOrNull(view.context, androidx.appcompat.R.attr.colorControlNormal)
                    val up = createButton(parent, R.drawable.tv_arrow_up, size, color) { move(recyclerView, callback, view, -1) }
                    val down = createButton(parent, R.drawable.tv_arrow_down, size, color) { move(recyclerView, callback, view, 1) }
                    up.id = R.id.tvMoveUp
                    down.id = R.id.tvMoveDown
                    parent.addView(up, index)
                    parent.addView(down, index + 1)
                    // the handle is kept (its visibility tells if the row can be moved) but takes no space
                    handle.layoutParams = handle.layoutParams.apply { width = 0 }
                    handle.setPadding(0, 0, 0, 0)
                    parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        parent.post { updateButtons(view, handle) }
                    }
                }
                updateButtons(view, handle)
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    private fun updateButtons(view: View, handle: View) {
        val visibility = if (handle.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.tvMoveUp)?.let { if (it.visibility != visibility) it.visibility = visibility }
        view.findViewById<View>(R.id.tvMoveDown)?.let { if (it.visibility != visibility) it.visibility = visibility }
    }

    private fun createButton(parent: ViewGroup, icon: Int, size: Int, tint: android.content.res.ColorStateList?, onClick: () -> Unit): ImageButton {
        return ImageButton(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(icon)
            imageTintList = tint
            setBackgroundResource(R.drawable.tv_list_button_background)
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun move(recyclerView: RecyclerView, callback: ItemTouchHelper.Callback, itemView: View, direction: Int) {
        val holder = recyclerView.findContainingViewHolder(itemView) ?: return
        val from = holder.bindingAdapterPosition
        val to = from + direction
        val count = recyclerView.adapter?.itemCount ?: 0
        if (from == RecyclerView.NO_POSITION || to < 0 || to >= count) {
            return
        }
        val target = recyclerView.findViewHolderForAdapterPosition(to)
        if (target == null) {
            recyclerView.scrollToPosition(to)
            return
        }
        callback.onMove(recyclerView, holder, target)
        recyclerView.scrollToPosition(to)
    }
}
