package com.parla.libreta.core.ink

import kotlin.math.max
import kotlin.math.min

/** A rectangle on a page, in page units, with [left] <= [right] and [top] <= [bottom]. */
data class InkRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    init {
        require(left <= right && top <= bottom) { "Inverted rectangle $this" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Big enough to hold something worth sending: a tap or a slip of the pen is not a selection. */
    val isUsable: Boolean get() = width >= MIN_SIDE && height >= MIN_SIDE

    /** Whether any ink of [page] falls inside, counting each stroke's thickness. */
    fun hasInk(page: InkPage): Boolean = page.strokes.any { stroke ->
        val reach = stroke.width / 2
        stroke.points.any { it.x >= left - reach && it.x <= right + reach && it.y >= top - reach && it.y <= bottom + reach }
    }

    /**
     * Pixels per page unit to render this area at: [preferred], or less so that the longer side stays within
     * [maxSide] pixels.
     */
    fun renderScale(preferred: Float, maxSide: Int): Float = min(preferred, maxSide / max(width, height))

    companion object {
        /** Shortest side of a usable selection, in page units: about a small letter. */
        const val MIN_SIDE = 12f

        /** The rectangle with corners ([x1], [y1]) and ([x2], [y2]), in either order, cut to the [page]. */
        fun between(x1: Float, y1: Float, x2: Float, y2: Float, page: InkPage): InkRect {
            val w = page.width.toFloat()
            val h = page.height.toFloat()
            return InkRect(
                left = min(x1, x2).coerceIn(0f, w),
                top = min(y1, y2).coerceIn(0f, h),
                right = max(x1, x2).coerceIn(0f, w),
                bottom = max(y1, y2).coerceIn(0f, h),
            )
        }
    }
}
