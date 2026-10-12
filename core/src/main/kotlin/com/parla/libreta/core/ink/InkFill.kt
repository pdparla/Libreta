package com.parla.libreta.core.ink

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The paint bucket (docs/15-relleno.md): the area around a point that the ink encloses, as bands of ink.
 *
 * The page is rasterised into a grid where the ink, other fills left out, is thickened by a margin that closes small
 * gaps in an outline; the free area connected to the point is flooded; and every few rows a thick horizontal band
 * covers each run of it. The bands' thickness and round ends give back the margin, so the fill reaches the ink.
 */
object InkFill {

    /** What filling does to a page: [bands] to add behind the ink and the old fill bands at [replaced] to remove. */
    class Result(val bands: List<Stroke>, val replaced: List<Int>)

    /** Gaps in an outline narrower than about twice this, in units on screen, do not let the fill out. */
    const val GAP = 3f

    /** The largest grid, in cells: a byte each, so a little over a megabyte. */
    const val MAX_CELLS = 1_500_000

    private const val FREE: Byte = 0
    private const val INK: Byte = 1
    private const val AREA: Byte = 2

    /**
     * Fills the area of [page] around ([x], [y]) with [color], seen at [zoom] (sizes are on screen, so a small shape
     * drawn zoomed in can be filled too). The bands share [startedAt], which identifies the fill. Null when the point
     * is on the ink or off the page.
     */
    fun at(page: InkPage, x: Float, y: Float, color: Int, zoom: Float = 1f, startedAt: Long): Result? {
        if (x < 0f || y < 0f || x >= page.width || y >= page.height) return null
        val cell = max(1f / zoom, sqrt(page.width.toFloat() * page.height / MAX_CELLS))
        val gap = max(GAP / zoom, cell)
        val step = max(gap / 2, cell)
        val grid = Grid(ceil(page.width / cell).toInt(), ceil(page.height / cell).toInt(), cell)
        page.strokes.forEach { if (!it.fill) grid.block(it, gap) }
        val seedX = min((x / cell).toInt(), grid.columns - 1)
        val seedY = min((y / cell).toInt(), grid.rows - 1)
        if (grid[seedX, seedY] != FREE) return null
        val (top, bottom) = grid.flood(seedX, seedY)

        val width = step + 2 * gap
        val rowStep = max(1, (step / cell).roundToInt())
        val half = rowStep / 2
        val bands = ArrayList<Stroke>()
        val covered = BooleanArray(grid.columns)
        var row = top + half
        while (row <= bottom + half) {
            val center = min(row, bottom)
            // Each band covers the rows around it: the union of their runs, so that thin slivers are not missed.
            covered.fill(false)
            for (r in max(top, center - half)..min(bottom, center + half)) {
                for (c in 0 until grid.columns) if (grid[c, r] == AREA) covered[c] = true
            }
            val bandY = (center + 0.5f) * cell
            var c = 0
            while (c < grid.columns) {
                if (!covered[c]) {
                    c++
                    continue
                }
                val start = c
                while (c < grid.columns && covered[c]) c++
                val left = (start + 0.5f) * cell
                val right = (c - 0.5f) * cell
                val points = if (start == c - 1) listOf(InkPoint(left, bandY, 0)) else listOf(InkPoint(left, bandY, 0), InkPoint(right, bandY, 0))
                bands += Stroke(color, width, startedAt, points, fill = true)
            }
            row += rowStep
        }
        val replaced = page.strokes.indices.filter { i ->
            val stroke = page.strokes[i]
            stroke.fill && grid.hasAreaAlong(stroke.points.first().x, stroke.points.last().x, stroke.points.first().y)
        }
        return Result(bands, replaced)
    }

    private class Grid(val columns: Int, val rows: Int, val cell: Float) {
        private val cells = ByteArray(columns * rows)

        operator fun get(c: Int, r: Int): Byte = cells[r * columns + c]

        /** Whether the area reaches the horizontal line from [x1] to [x2] at [y]: an old band it overlaps. */
        fun hasAreaAlong(x1: Float, x2: Float, y: Float): Boolean {
            val r = (y / cell).toInt()
            if (r !in 0 until rows) return false
            val from = max(0, (min(x1, x2) / cell).toInt())
            val to = min(columns - 1, (max(x1, x2) / cell).toInt())
            return (from..to).any { get(it, r) == AREA }
        }

        /** Marks as ink every cell whose centre lies within the stroke's half thickness plus [gap] of its line. */
        fun block(stroke: Stroke, gap: Float) {
            val reach = stroke.width / 2 + gap
            val points = stroke.points
            if (points.size == 1) return blockSegment(points[0], points[0], reach)
            for (i in 1 until points.size) blockSegment(points[i - 1], points[i], reach)
        }

        private fun blockSegment(a: InkPoint, b: InkPoint, reach: Float) {
            val fromC = max(0, floor((min(a.x, b.x) - reach) / cell).toInt())
            val toC = min(columns - 1, floor((max(a.x, b.x) + reach) / cell).toInt())
            val fromR = max(0, floor((min(a.y, b.y) - reach) / cell).toInt())
            val toR = min(rows - 1, floor((max(a.y, b.y) + reach) / cell).toInt())
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lengthSquared = dx * dx + dy * dy
            val limit = reach * reach
            for (r in fromR..toR) {
                val py = (r + 0.5f) * cell
                for (c in fromC..toC) {
                    val index = r * columns + c
                    if (cells[index] == INK) continue
                    val px = (c + 0.5f) * cell
                    val t = if (lengthSquared == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
                    val ex = px - (a.x + t * dx)
                    val ey = py - (a.y + t * dy)
                    if (ex * ex + ey * ey <= limit) cells[index] = INK
                }
            }
        }

        /** Floods the free cells connected to ([c], [r]), a row span at a time; returns the first and last rows reached. */
        fun flood(c: Int, r: Int): Pair<Int, Int> {
            var stack = IntArray(256)
            var size = 0
            fun push(index: Int) {
                if (size == stack.size) stack = stack.copyOf(size * 2)
                stack[size++] = index
            }
            var top = r
            var bottom = r
            push(r * columns + c)
            while (size > 0) {
                val index = stack[--size]
                if (cells[index] != FREE) continue
                val row = index / columns
                var left = index % columns
                var right = left
                while (left > 0 && cells[row * columns + left - 1] == FREE) left--
                while (right < columns - 1 && cells[row * columns + right + 1] == FREE) right++
                for (x in left..right) cells[row * columns + x] = AREA
                top = min(top, row)
                bottom = max(bottom, row)
                for (next in intArrayOf(row - 1, row + 1)) {
                    if (next < 0 || next >= rows) continue
                    var x = left
                    while (x <= right) {
                        if (cells[next * columns + x] == FREE) {
                            push(next * columns + x)
                            while (x <= right && cells[next * columns + x] == FREE) x++
                        } else {
                            x++
                        }
                    }
                }
            }
            return top to bottom
        }
    }
}
