package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class TextWithShadow : AppCompatTextView {

    private val isTvDevice = resources.getBoolean(com.github.andreyasadchy.xtra.R.bool.isTv)

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        if (isTvDevice) {
            paint.isAntiAlias = true
            paint.isSubpixelText = true
            setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (isTvDevice) {
            super.onDraw(canvas)
        } else {
            for (i in 0..6) {
                super.onDraw(canvas)
            }
        }
    }
}