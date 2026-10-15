package com.github.andreyasadchy.xtra.model.ui

class SettingsDragListItem(
    val key: String,
    val text: String,
    var default: Boolean,
    var enabled: Boolean,
    /** Key of the row this one belongs to: shown under it, smaller and moved in, only checked (not moved). */
    val parent: String? = null,
)