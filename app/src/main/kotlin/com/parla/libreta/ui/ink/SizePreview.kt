package com.parla.libreta.ui.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A sample of a pen or eraser size (docs/16-grosor.md): a dot as wide as the pen's stroke, in its colour, or a ring as
 * big as the eraser. One that does not fit stays as big as the view: the number beside it tells the size.
 */
class SizePreview(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Diameter on screen, in pixels. */
    var diameter = 0f
        set(value) {
            field = value
            invalidate()
        }

    var color = 0xff202124.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /** A ring, like the eraser on the page, instead of a dot. */
    var outline = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        paint.color = if (outline) 0xff5f6368.toInt() else color
        paint.style = if (outline) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = resources.displayMetrics.density
        val room = minOf(width, height) - 2 * paint.strokeWidth
        canvas.drawCircle(width / 2f, height / 2f, minOf(diameter, room) / 2, paint)
    }
}
