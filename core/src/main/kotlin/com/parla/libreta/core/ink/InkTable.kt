package com.parla.libreta.core.ink

import kotlin.math.max
import kotlin.math.min

/**
 * A table drawn by hand made clean (docs/design/ink.md#clean-tables): the AI reads it as a Markdown table, which goes
 * into the note's text, and the page gets straight grid lines with typed text in each cell, in place of the strokes.
 */
object InkTable {

    /**
     * The cells of the first Markdown table in [markdown], row by row, every row as long as the longest; null if
     * there is none. The separator row (`|---|`) is left out, `\|` is a bar inside a cell and `<br>` a line break.
     */
    fun parse(markdown: String): List<List<String>>? {
        val lines = markdown.lines().map { it.trim() }.dropWhile { !it.startsWith("|") }.takeWhile { it.startsWith("|") }
        val rows = lines.filterNot(::isSeparator).map(::cellsOf)
        if (rows.isEmpty()) return null
        val columns = rows.maxOf { it.size }
        if (columns == 0) return null
        return rows.map { row -> row + List(columns - row.size) { "" } }
    }

    private val SEPARATOR = Regex("""^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?$""")
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    private fun isSeparator(line: String) = SEPARATOR.matches(line)

    private fun cellsOf(line: String): List<String> {
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        val body = line.removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length && body[i + 1] == '|' -> { current.append('|'); i++ }
                c == '|' -> { cells += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells.map { it.trim().replace(BREAK, "\n") }
    }

    /** [rows] as a Markdown table, the first row as its header, for the note's text. */
    fun toMarkdown(rows: List<List<String>>): String {
        fun line(cells: List<String>) = cells.joinToString(" | ", "| ", " |") { it.replace("|", "\\|").replace("\n", "<br>") }
        val header = rows.first()
        return (listOf(line(header), line(header.map { "---" })) + rows.drop(1).map(::line)).joinToString("\n")
    }

    /**
     * Where a clean table goes: its font [size], the x of each column edge ([columns], left to right, one more than
     * there are columns) and the y of each row edge ([rows]).
     */
    data class Layout(val size: Float, val columns: List<Float>, val rows: List<Float>) {
        val box: InkRect get() = InkRect(columns.first(), rows.first(), columns.last(), rows.last())
    }

    /**
     * Lays out [cells] in [area], where the table was drawn. [measure] gives the width and height of a cell's text at
     * font size 1. Each column is as wide as its widest text and each row as tall as its tallest, plus padding; the
     * font size is the largest at which that fits in [area], within [MIN_SIZE] and [MAX_SIZE]; and the spare room is
     * shared out in proportion, so that the clean table covers the drawn one.
     */
    fun layout(cells: List<List<String>>, area: InkRect, measure: (String) -> Pair<Float, Float>): Layout {
        val columnCount = cells.first().size
        val sizes = cells.map { row -> row.map(measure) }
        val widths = (0 until columnCount).map { c -> sizes.maxOf { it[c].first } + 2 * PADDING_X }
        val heights = sizes.map { row -> max(row.maxOf { it.second }, 1f) + 2 * PADDING_Y }
        val size = min(area.width / widths.sum(), area.height / heights.sum()).coerceIn(MIN_SIZE, MAX_SIZE)
        // The text at [size] fits; what is left over widens the columns and heightens the rows in proportion.
        val stretchX = max(1f, area.width / (widths.sum() * size))
        val stretchY = max(1f, area.height / (heights.sum() * size))
        return Layout(size, edges(area.left, widths.map { it * size * stretchX }), edges(area.top, heights.map { it * size * stretchY }))
    }

    private fun edges(start: Float, lengths: List<Float>): List<Float> {
        val result = ArrayList<Float>(lengths.size + 1)
        var at = start
        result += at
        for (length in lengths) {
            at += length
            result += at
        }
        return result
    }

    /** Where the text of the cell in [row] and [column] starts: padded from the left, centred in the row's height. */
    fun textOrigin(layout: Layout, row: Int, column: Int, textHeight: Float): Pair<Float, Float> {
        val top = layout.rows[row]
        val height = layout.rows[row + 1] - top
        return (layout.columns[column] + PADDING_X * layout.size) to (top + (height - textHeight * layout.size) / 2)
    }

    /** The grid of [layout] as straight strokes of [width] and [color]: every row edge, then every column edge. */
    fun gridStrokes(layout: Layout, color: Int, width: Float, startedAt: Long): List<Stroke> {
        val box = layout.box
        val horizontal = layout.rows.map { y -> listOf(InkPoint(box.left, y, 0), InkPoint(box.right, y, 0)) }
        val vertical = layout.columns.map { x -> listOf(InkPoint(x, box.top, 0), InkPoint(x, box.bottom, 0)) }
        return (horizontal + vertical).mapIndexed { i, points -> Stroke(color, width, startedAt + i, points) }
    }

    /** Space between a cell's text and its lines, in font sizes. */
    const val PADDING_X = 0.4f
    const val PADDING_Y = 0.25f

    const val MIN_SIZE = 8f
    const val MAX_SIZE = 60f
}
