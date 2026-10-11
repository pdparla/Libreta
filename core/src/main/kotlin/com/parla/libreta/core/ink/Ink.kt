package com.parla.libreta.core.ink

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A sampled pen position, in page units (1 unit = 1 dp on the tablet), [t] milliseconds after the stroke began. */
data class InkPoint(val x: Float, val y: Float, val t: Int)

/**
 * One pen stroke. Timestamps are kept so the strokes can later be fed to handwriting recognition, which reads
 * pen order and speed as well as shape.
 *
 * @param color ARGB colour.
 * @param startedAt wall-clock time of the first point, in epoch milliseconds.
 */
data class Stroke(val color: Int, val width: Float, val startedAt: Long, val points: List<InkPoint>) {

    init {
        require(points.isNotEmpty()) { "A stroke has at least one point" }
    }

    /** The rectangle around the stroke, thickness included; computed once, since strokes never change. */
    val bounds: InkRect by lazy { InkRect.around(listOf(this))!! }

    /**
     * The stroke scaled by [scale] around ([pivotX], [pivotY]) and then moved by ([dx], [dy]); its thickness scales
     * too. Timestamps are kept: moving ink does not change when it was written.
     */
    fun transformed(dx: Float, dy: Float, scale: Float = 1f, pivotX: Float = 0f, pivotY: Float = 0f): Stroke =
        copy(
            width = width * scale,
            points = points.map { InkPoint(pivotX + (it.x - pivotX) * scale + dx, pivotY + (it.y - pivotY) * scale + dy, it.t) },
        )

    /** Whether some point of the stroke, or its thickness around it, falls inside [area]. */
    fun touches(area: InkRect): Boolean {
        val reach = width / 2
        return points.any { it.x >= area.left - reach && it.x <= area.right + reach && it.y >= area.top - reach && it.y <= area.bottom + reach }
    }

    /** Whether the pen passed within [radius] of ([x], [y]), counting the stroke's own thickness. */
    fun isHitBy(x: Float, y: Float, radius: Float): Boolean {
        val reach = radius + width / 2
        if (points.size == 1) return distanceSquared(x, y, points[0].x, points[0].y) <= reach * reach
        for (i in 1 until points.size) {
            if (segmentDistanceSquared(x, y, points[i - 1], points[i]) <= reach * reach) return true
        }
        return false
    }

    /**
     * What is left of the stroke after rubbing out the disc of [radius] around ([x], [y]), counting the stroke's own
     * thickness: the stroke itself if the eraser misses it, nothing if it covers it, or the pieces on either side.
     * Each piece starts its own clock, so its timestamps keep meaning "milliseconds after the stroke began".
     */
    fun erasedAt(x: Float, y: Float, radius: Float): List<Stroke> {
        val reach = radius + width / 2
        if (points.size == 1) return if (distanceSquared(x, y, points[0].x, points[0].y) <= reach * reach) emptyList() else listOf(this)
        val pieces = ArrayList<List<InkPoint>>()
        var current: MutableList<InkPoint>? = if (distanceSquared(x, y, points[0].x, points[0].y) < reach * reach) null else mutableListOf(points[0])
        var touched = current == null
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val inside = insideInterval(x, y, reach, a, b)
            if (inside == null) {
                current?.add(b) ?: run { current = mutableListOf(a, b) }
                continue
            }
            touched = true
            val (enter, exit) = inside
            current?.let {
                if (enter > 0f) it += lerp(a, b, enter)
                pieces += it
            }
            current = if (exit < 1f) mutableListOf(lerp(a, b, exit), b) else null
        }
        if (!touched) return listOf(this)
        current?.let { pieces += it }
        return pieces.filter { length(it) >= MIN_PIECE_LENGTH }.map { piece ->
            val start = piece[0].t
            Stroke(color, width, startedAt + start, piece.map { it.copy(t = it.t - start) })
        }
    }

    /** The part of segment [a]-[b], as fractions of its length, that lies strictly inside the disc; null if none. */
    private fun insideInterval(x: Float, y: Float, reach: Float, a: InkPoint, b: InkPoint): Pair<Float, Float>? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val fx = a.x - x
        val fy = a.y - y
        val qa = dx * dx + dy * dy
        val qb = 2 * (fx * dx + fy * dy)
        val qc = fx * fx + fy * fy - reach * reach
        if (qa == 0f) return if (qc < 0f) 0f to 1f else null
        val discriminant = qb * qb - 4 * qa * qc
        if (discriminant <= 0f) return null
        val root = sqrt(discriminant)
        val enter = ((-qb - root) / (2 * qa)).coerceAtLeast(0f)
        val exit = ((-qb + root) / (2 * qa)).coerceAtMost(1f)
        return if (enter < exit) enter to exit else null
    }

    private fun lerp(a: InkPoint, b: InkPoint, f: Float) =
        InkPoint(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.t + ((b.t - a.t) * f).roundToInt())

    private fun length(piece: List<InkPoint>): Float =
        (1 until piece.size).sumOf { hypot(piece[it].x - piece[it - 1].x, piece[it].y - piece[it - 1].y).toDouble() }.toFloat()

    private fun segmentDistanceSquared(x: Float, y: Float, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return distanceSquared(x, y, a.x, a.y)
        val t = (((x - a.x) * dx + (y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return distanceSquared(x, y, a.x + t * dx, a.y + t * dy)
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private companion object {
        /** Crumbs shorter than this (in page units) left at the edge of the eraser are dropped. */
        const val MIN_PIECE_LENGTH = 0.5f
    }
}

/** Background printed on a page, like the paper templates of a notebook. */
enum class Paper(val code: String) {
    BLANK("blank"),
    LINED("lined"),
    GRID("grid"),
    ;

    companion object {
        fun ofCode(code: String): Paper = values().firstOrNull { it.code == code } ?: BLANK
    }
}

data class InkPage(val width: Int, val height: Int, val paper: Paper, val strokes: List<Stroke> = emptyList()) {

    init {
        require(width > 0 && height > 0) { "Page size must be positive" }
    }

    val isEmpty: Boolean get() = strokes.isEmpty()
}

/** A handwritten note: one or more pages, all the same size. */
data class InkDocument(val pages: List<InkPage>) {

    init {
        require(pages.isNotEmpty()) { "A document has at least one page" }
    }

    companion object {
        fun blank(width: Int, height: Int, paper: Paper = Paper.LINED) = InkDocument(listOf(InkPage(width, height, paper)))
    }
}
