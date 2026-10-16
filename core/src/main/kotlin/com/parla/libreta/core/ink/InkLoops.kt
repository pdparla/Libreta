package com.parla.libreta.core.ink

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Circled writing (docs/design/ink.md#circled-questions): a question written on the page and circled is answered
 * by the AI. This decides, without the AI, whether a stroke is such a circle and what it holds, so that only
 * likely questions cost a request.
 */
object InkLoops {

    /**
     * Whether [stroke] closes on itself around something: pen ink, at least [MIN_SIDE] units across and [MIN_THIN]
     * thick, whose ends meet within [CLOSE_SHARE] of its larger side, and whose length goes most of the way round
     * its box (a scribble back and forth, or a line, does not).
     */
    fun isLoop(stroke: Stroke): Boolean {
        if (stroke.fill || stroke.isText || stroke.points.size < MIN_POINTS) return false
        val box = stroke.bounds
        val larger = max(box.width, box.height)
        if (larger < MIN_SIDE || min(box.width, box.height) < MIN_THIN) return false
        val first = stroke.points.first()
        val last = stroke.points.last()
        if (hypot(last.x - first.x, last.y - first.y) > larger * CLOSE_SHARE) return false
        var length = 0f
        for (i in 1 until stroke.points.size) {
            length += hypot(stroke.points[i].x - stroke.points[i - 1].x, stroke.points[i].y - stroke.points[i - 1].y)
        }
        return length >= (box.width + box.height) * ROUND_SHARE
    }

    /**
     * The strokes of [page] inside [loop], as indexes: those, other than the loop, whose box centre is inside it
     * (the loop is closed from its last point to its first) and whose box is within the loop's box. Fills are left
     * out: they are colour, not writing.
     */
    fun enclosed(page: InkPage, loop: Stroke): List<Int> {
        val outer = loop.bounds
        return page.strokes.indices.filter { i ->
            val stroke = page.strokes[i]
            if (stroke === loop || stroke.fill) return@filter false
            val box = stroke.bounds
            box.left >= outer.left && box.right <= outer.right && box.top >= outer.top && box.bottom <= outer.bottom &&
                contains(loop.points, (box.left + box.right) / 2, (box.top + box.bottom) / 2)
        }
    }

    /** Whether the most recent stroke on [page] circles a question worth asking: a loop around [MIN_INSIDE] strokes. */
    fun circledQuestion(page: InkPage): Stroke? {
        val loop = page.strokes.lastOrNull()?.takeIf(::isLoop) ?: return null
        return loop.takeIf { enclosed(page, it).size >= MIN_INSIDE }
    }

    /** Point in polygon by ray casting; the polygon closes from its last point back to its first. */
    private fun contains(polygon: List<InkPoint>, x: Float, y: Float): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    const val MIN_POINTS = 8
    const val MIN_SIDE = 40f
    const val MIN_THIN = 15f

    /** How far apart a loop's ends may be, as a share of its larger side: a hand-drawn circle seldom closes exactly. */
    const val CLOSE_SHARE = 0.3f

    /** How much of its box's half-perimeter (width plus height) a loop's length covers, at least: a circle is ~1.57. */
    const val ROUND_SHARE = 1.3f

    /** Strokes a loop must hold to be a question: a question mark alone is two. */
    const val MIN_INSIDE = 2
}
