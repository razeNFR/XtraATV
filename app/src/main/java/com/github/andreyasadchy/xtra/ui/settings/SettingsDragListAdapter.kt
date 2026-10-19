package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.SettingsDragListItemBinding
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem

class SettingsDragListAdapter : ListAdapter<SettingsDragListItem, SettingsDragListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<SettingsDragListItem>() {
        override fun areItemsTheSame(oldItem: SettingsDragListItem, newItem: SettingsDragListItem): Boolean {
            return oldItem.key == newItem.key
        }

        override fun areContentsTheSame(oldItem: SettingsDragListItem, newItem: SettingsDragListItem): Boolean {
            return true
        }
    }
) {
    var itemTouchHelper: ItemTouchHelper? = null
    var setDefault: ((SettingsDragListItem) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = SettingsDragListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: SettingsDragListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: SettingsDragListItem) {
            with(binding) {
                image.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(this@ViewHolder)
                    }
                    false
                }
                text.text = item.text
                // a row under another one: moved in, smaller, no handle and no default (it follows its row)
                val sub = item.parent != null
                val density = root.resources.displayMetrics.density
                root.setPaddingRelative(((if (sub) 44 else 10) * density).toInt(), root.paddingTop, root.paddingEnd, root.paddingBottom)
                text.textSize = if (sub) 13f else 14f
                image.visibility = if (sub || item.locked) View.INVISIBLE else View.VISIBLE
                if (setDefault != null && !sub) {
                    // no house on an unchecked tab: it can't be the one opened first
                    setAsDefault.visibility = if (item.enabled) View.VISIBLE else View.INVISIBLE
                    setAsDefault.setOnClickListener {
                        setAsDefault.setImageResource(R.drawable.baseline_home_black_24)
                        setAsDefault.isClickable = false
                        setDefault!!(item)
                    }
                    if (item.default) {
                        setAsDefault.setImageResource(R.drawable.baseline_home_black_24)
                        setAsDefault.isClickable = false
                    } else {
                        setAsDefault.setImageResource(R.drawable.outline_home_black_24)
                        setAsDefault.isClickable = true
                    }
                } else {
                    setAsDefault.visibility = View.GONE
                }
                // the listener of the row shown before is removed first, setting the box does not change that other row
                checkBox.setOnCheckedChangeListener(null)
                checkBox.visibility = if (item.locked) View.INVISIBLE else View.VISIBLE
                checkBox.isChecked = item.enabled
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    item.enabled = isChecked
                    if (setDefault != null && !sub) {
                        setAsDefault.visibility = if (isChecked) View.VISIBLE else View.INVISIBLE
                        val rows = currentList
                        if (!isChecked && item.default) {
                            // the tab opened first was unchecked: the house goes to the first checked tab
                            rows.firstOrNull { it !== item && it.enabled && it.parent == null }?.let { next ->
                                setDefault!!(next)
                                root.post { notifyItemChanged(rows.indexOf(next)) }
                            }
                        } else if (isChecked && rows.none { it.default && it.enabled && it.parent == null }) {
                            // no checked tab had the house: this one takes it
                            setDefault!!(item)
                            setAsDefault.setImageResource(R.drawable.baseline_home_black_24)
                            setAsDefault.isClickable = false
                        }
                    }
                }
            }
        }
    }
}