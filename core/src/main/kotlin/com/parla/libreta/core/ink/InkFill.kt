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

    /**
     * What filling does to a page: [bands] to add behind the ink, and the old fill bands that cross the area, by index,
     * each with the pieces of it left outside the area (none when it lay all inside).
     */
    class Result(val bands: List<Stroke>, val replaced: Map<Int, List<Stroke>>)

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
        val area = grid.flood(seedX, seedY)
        val top = area.top
        val bottom = area.bottom

        // The margin given back is the gap plus a cell: the first free cell can be up to a cell further than the gap.
        val width = step + 2 * (gap + cell)
        val rowStep = max(1, (step / cell).roundToInt())
        val half = rowStep / 2
        val bands = ArrayList<Stroke>()
        val covered = BooleanArray(grid.columns)
        // The first band sits on the top row and the last on the bottom one, so the fill reaches flat edges of the ink.
        var row = top
        while (row <= bottom + half) {
            val center = min(row, bottom)
            // Each band covers the rows around it: the union of their runs, so that thin slivers are not missed.
            covered.fill(false, area.left, area.right + 1)
            for (r in max(top, center - half)..min(bottom, center + half)) grid.cover(r, area.left, area.right, covered)
            val bandY = (center + 0.5f) * cell
            // The area lies between its leftmost and rightmost columns, so nothing outside them needs a look.
            var c = area.left
            while (c <= area.right) {
                if (!covered[c]) {
                    c++
                    continue
                }
                val start = c
                while (c <= area.right && covered[c]) c++
                val left = (start + 0.5f) * cell
                val right = (c - 0.5f) * cell
                val points = if (start == c - 1) listOf(InkPoint(left, bandY, 0)) else listOf(InkPoint(left, bandY, 0), InkPoint(right, bandY, 0))
                bands += Stroke(color, width, startedAt, points, fill = true)
            }
            row += rowStep
        }
        // An old band only loses the stretch inside the area: filling a shape drawn inside an older fill must not wipe
        // out the older fill's rows from one side to the other.
        val replaced = LinkedHashMap<Int, List<Stroke>>()
        page.strokes.forEachIndexed { i, stroke ->
            if (stroke.fill) grid.outsideArea(stroke)?.let { replaced[i] = it }
        }
        return Result(bands, replaced)
    }

    /** The rows and columns the flood reached, all inclusive. */
    private class Extent(val top: Int, val bottom: Int, val left: Int, val right: Int)

    private class Grid(val columns: Int, val rows: Int, val cell: Float) {
        private val cells = ByteArray(columns * rows)

        operator fun get(c: Int, r: Int): Byte = cells[r * columns + c]

        /** Marks in [covered] the columns from [from] to [to] that row [r] has in the area. */
        fun cover(r: Int, from: Int, to: Int, covered: BooleanArray) {
            val base = r * columns
            for (c in from..to) if (cells[base + c] == AREA) covered[c] = true
        }

        /**
         * The pieces of an old fill [band] that lie outside the area, or null when the area does not reach its line.
         * Pieces over nothing but ink (a sliver between two parts of the area) are dropped with the rest.
         */
        fun outsideArea(band: Stroke): List<Stroke>? {
            val y = band.points.first().y
            val r = (y / cell).toInt()
            if (r !in 0 until rows) return null
            val x1 = min(band.points.first().x, band.points.last().x)
            val x2 = max(band.points.first().x, band.points.last().x)
            val from = max(0, (x1 / cell).toInt())
            val to = min(columns - 1, (x2 / cell).toInt())
            if ((from..to).none { get(it, r) == AREA }) return null
            val pieces = ArrayList<Stroke>()
            var c = from
            while (c <= to) {
                if (get(c, r) == AREA) {
                    c++
                    continue
                }
                val start = c
                var free = false
                while (c <= to && get(c, r) != AREA) {
                    if (get(c, r) == FREE) free = true
                    c++
                }
                if (!free) continue
                // The ends of the band itself stay where they were; the cut ends sit at the edge of the area.
                val left = if (start == from) x1 else (start + 0.5f) * cell
                val right = if (c > to) x2 else (c - 0.5f) * cell
                val points = if (left == right) listOf(InkPoint(left, y, 0)) else listOf(InkPoint(left, y, 0), InkPoint(right, y, 0))
                pieces += band.copy(points = points)
            }
            return pieces
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

        /** Floods the free cells connected to ([c], [r]), a row span at a time; returns the rows and columns reached. */
        fun flood(c: Int, r: Int): Extent {
            var stack = IntArray(256)
            var size = 0
            fun push(index: Int) {
                if (size == stack.size) stack = stack.copyOf(size * 2)
                stack[size++] = index
            }
            var top = r
            var bottom = r
            var leftmost = c
            var rightmost = c
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
                leftmost = min(leftmost, left)
                rightmost = max(rightmost, right)
                var next = row - 1
                while (next <= row + 1) {
                    if (next < 0 || next >= rows) {
                        next += 2
                        continue
                    }
                    var x = left
                    while (x <= right) {
                        if (cells[next * columns + x] == FREE) {
                            push(next * columns + x)
                            while (x <= right && cells[next * columns + x] == FREE) x++
                        } else {
                            x++
                        }
                    }
                    next += 2
                }
            }
            return Extent(top, bottom, leftmost, rightmost)
        }
    }
}
