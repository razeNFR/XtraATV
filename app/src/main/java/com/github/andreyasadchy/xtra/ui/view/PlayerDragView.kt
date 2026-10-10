package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * Touch surface of the player, also used as the D-pad focus target of the player.
 *
 * The minimized player is moved with translation and scale, which focus search ignores,
 * so the focused rect is adjusted to the real position on screen to make the minimized player
 * reachable with the D-pad.
 */
class PlayerDragView : View {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private val rectF = RectF()

    override fun getFocusedRect(r: Rect) {
        super.getFocusedRect(r)
        rectF.set(r)
        var offsetX = 0f
        var offsetY = 0f
        var view: View = this
        while (true) {
            view.matrix.mapRect(rectF)
            val parent = view.parent as? View ?: break
            val dx = (view.left - parent.scrollX).toFloat()
            val dy = (view.top - parent.scrollY).toFloat()
            rectF.offset(dx, dy)
            offsetX += dx
            offsetY += dy
            view = parent
        }
        rectF.offset(-offsetX, -offsetY)
        r.set(rectF.left.roundToInt(), rectF.top.roundToInt(), rectF.right.roundToInt(), rectF.bottom.roundToInt())
    }
}
