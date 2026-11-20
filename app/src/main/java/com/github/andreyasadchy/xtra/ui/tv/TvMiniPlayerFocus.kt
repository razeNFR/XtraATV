package com.github.andreyasadchy.xtra.ui.tv

import android.graphics.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import kotlin.math.roundToInt

/**
 * Tracks the window-space bounds of the currently focused Compose card so the minimized TV player
 * can smoothly fade to 20% ghost opacity whenever a focused card sits behind it, without moving.
 */
object TvMiniPlayerFocus {
    private var activeToken: Any? = null
    val focusedCardRect = Rect()
    var hasFocusedCard = false
        private set
    var onChanged: (() -> Unit)? = null

    fun reportCoordinates(token: Any, coords: LayoutCoordinates) {
        if (!coords.isAttached) return
        val b = coords.boundsInWindow()
        if (b.isEmpty) return
        updateCard(
            token,
            b.left.roundToInt(),
            b.top.roundToInt(),
            b.right.roundToInt(),
            b.bottom.roundToInt(),
        )
    }

    fun updateCard(token: Any, left: Int, top: Int, right: Int, bottom: Int) {
        activeToken = token
        val changed = !hasFocusedCard ||
            focusedCardRect.left != left ||
            focusedCardRect.top != top ||
            focusedCardRect.right != right ||
            focusedCardRect.bottom != bottom
        focusedCardRect.set(left, top, right, bottom)
        hasFocusedCard = true
        if (changed) {
            onChanged?.invoke()
        }
    }

    fun clearCard(token: Any) {
        if (activeToken === token && hasFocusedCard) {
            activeToken = null
            hasFocusedCard = false
            focusedCardRect.setEmpty()
            onChanged?.invoke()
        }
    }
}
