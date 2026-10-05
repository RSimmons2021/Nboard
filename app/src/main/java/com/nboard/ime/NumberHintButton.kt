package com.nboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.appcompat.widget.AppCompatButton

class NumberHintButton(context: Context) : AppCompatButton(context) {
    var numberHint: String? = null
        set(value) {
            field = value
            contentDescription = value?.let { "$text, hold for $it" }
            invalidate()
        }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        numberHint?.let { hint ->
            hintPaint.color = currentTextColor
            hintPaint.alpha = 150
            hintPaint.textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics
            )
            hintPaint.typeface = typeface
            hintPaint.textAlign = Paint.Align.RIGHT
            val inset = 5f * resources.displayMetrics.density
            canvas.drawText(hint, width - inset, inset - hintPaint.fontMetrics.top, hintPaint)
        }
    }
}
