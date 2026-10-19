package com.github.andreyasadchy.xtra.model.ui

class SettingsDragListItem(
    val key: String,
    val text: String,
    var default: Boolean,
    var enabled: Boolean,
    /** Key of the row this one belongs to: shown under it, smaller and moved in, only checked (not moved). */
    val parent: String? = null,
    /** A row that can't be unchecked nor moved (the home of the TV in the navigation tabs), it can only take the default. */
    val locked: Boolean = false,
)