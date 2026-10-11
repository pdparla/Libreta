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
    fun hasInk(page: InkPage): Boolean = page.strokes.any { it.touches(this) }

    /**
     * The strokes of [page] this rectangle selects, as indexes into [InkPage.strokes]: every stroke with some ink
     * inside, whole. Strokes are not cut at the edge: moving half a letter makes no sense.
     */
    fun selectedStrokes(page: InkPage): List<Int> = page.strokes.indices.filter { page.strokes[it].touches(this) }

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** The rectangle scaled by [scale] around its top-left corner and then moved by ([dx], [dy]). */
    fun transformed(dx: Float, dy: Float, scale: Float = 1f): InkRect =
        InkRect(left + dx, top + dy, left + width * scale + dx, top + height * scale + dy)

    /** ([dx], [dy]) limited so that the moved rectangle stays on [page]; no movement on an axis it does not fit. */
    fun moveWithin(dx: Float, dy: Float, page: InkPage): Pair<Float, Float> {
        fun limit(d: Float, low: Float, high: Float, size: Int) = if (high - low > size) 0f else d.coerceIn(-low, size - high)
        return limit(dx, left, right, page.width) to limit(dy, top, bottom, page.height)
    }

    /**
     * [scale] (around the top-left corner) limited so that the rectangle stays on [page] and does not shrink below
     * [MIN_SIDE] on its longer side.
     */
    fun scaleWithin(scale: Float, page: InkPage): Float {
        val longer = max(width, height)
        if (longer <= 0f) return 1f
        val smallest = MIN_SIDE / longer
        val largest = min(if (width > 0f) (page.width - left) / width else Float.MAX_VALUE, if (height > 0f) (page.height - top) / height else Float.MAX_VALUE)
        if (largest < smallest) return min(1f, largest).coerceAtLeast(0f)
        return scale.coerceIn(smallest, largest)
    }

    /**
     * Pixels per page unit to render this area at: [preferred], or less so that the longer side stays within
     * [maxSide] pixels.
     */
    fun renderScale(preferred: Float, maxSide: Int): Float = min(preferred, maxSide / max(width, height))

    companion object {
        /** Shortest side of a usable selection, in page units: about a small letter. */
        const val MIN_SIDE = 12f

        /** The smallest rectangle around [strokes], their thickness included; null for none. */
        fun around(strokes: List<Stroke>): InkRect? {
            if (strokes.isEmpty()) return null
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (stroke in strokes) {
                val reach = stroke.width / 2
                for (p in stroke.points) {
                    left = min(left, p.x - reach)
                    top = min(top, p.y - reach)
                    right = max(right, p.x + reach)
                    bottom = max(bottom, p.y + reach)
                }
            }
            return InkRect(left, top, right, bottom)
        }

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
