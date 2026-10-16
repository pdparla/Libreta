package com.parla.libreta.core.ink

import kotlin.math.max
import kotlin.math.min

/**
 * Drawings in a clean copy (docs/design/ai.md#clean-copy). The model looks at the page images and writes, where a
 * drawing belongs, a placeholder such as `![Esquema del circuito](dibujo:2,120,340,560,800)`: the 1-based page and
 * the corners of the drawing in thousandths of that page image's width and height ([InkFrame.fromThousandths]). The
 * app turns each one into a picture of the strokes there.
 */
object InkFigures {

    /** A placeholder in the model's Markdown: where it is ([range]), its [caption], its 0-based [page] and corners. */
    data class Placeholder(val range: IntRange, val caption: String, val page: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private val PLACEHOLDER = Regex(
        """!\[((?:[^\[\]\n]|\[[^\[\]\n]*])*)]\(\s*<?\s*dibujo:\s*(\d+)\s*[,;]\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*>?\s*\)""",
    )

    /** The placeholders in [markdown], in order. */
    fun placeholders(markdown: String): List<Placeholder> = PLACEHOLDER.findAll(markdown).map { match ->
        val v = match.groupValues
        Placeholder(match.range, v[1].trim(), v[2].toInt() - 1, v[3].toFloat(), v[4].toFloat(), v[5].toFloat(), v[6].toFloat())
    }.toList()

    /**
     * [markdown] with each placeholder replaced by what [replacement] returns for it (its index among them); null
     * removes it.
     */
    fun replace(markdown: String, replacement: (Int, Placeholder) -> String?): String {
        val found = placeholders(markdown)
        if (found.isEmpty()) return markdown
        val out = StringBuilder(markdown.length)
        var from = 0
        found.forEachIndexed { i, placeholder ->
            out.append(markdown, from, placeholder.range.first)
            replacement(i, placeholder)?.let(out::append)
            from = placeholder.range.last + 1
        }
        out.append(markdown, from, markdown.length)
        // A removed placeholder can leave a run of blank lines.
        return out.toString().replace(BLANK_LINES, "\n\n").trim()
    }

    private val BLANK_LINES = Regex("""\n[ \t]*\n(?:[ \t]*\n)+""")

    /**
     * The strokes of [page] that make up the drawing the model framed in [area], as indexes. The model's frames are
     * approximate, and a drawing often shares its height with a line of writing, so:
     *
     * - [area] is first widened by [SLACK] units, since the model's corners tend to cut the drawing's edges;
     * - a stroke is in when at least half of its box is, so that a frame a little off still takes the whole drawing;
     * - but handwriting goes by whole words: small strokes ([LETTER_SIZE]) that nearly touch ([WORD_GAP]) are judged
     *   together, by the box around them, so that the edge of the frame never cuts a word in two;
     * - fills go whole.
     */
    fun strokesIn(page: InkPage, area: InkRect): List<Int> {
        val wide = InkRect(area.left - SLACK, area.top - SLACK, area.right + SLACK, area.bottom + SLACK)
        val strokes = page.strokes
        val isLetter = BooleanArray(strokes.size) { i ->
            val stroke = strokes[i]
            !stroke.fill && !stroke.isText && max(stroke.bounds.width, stroke.bounds.height) <= LETTER_SIZE
        }
        val chosen = HashSet<Int>()
        val judged = BooleanArray(strokes.size)
        for (i in strokes.indices) {
            if (judged[i] || !strokes[i].bounds.intersects(wide)) continue
            if (!isLetter[i]) {
                if (mostlyInside(strokes[i].bounds, wide)) chosen += i
                continue
            }
            val word = wordOf(strokes, isLetter, i)
            word.forEach { judged[it] = true }
            if (mostlyInside(InkRect.around(word.map { strokes[it] })!!, wide)) chosen += word
        }
        return page.withWholeFills(chosen.sorted())
    }

    /** The letters of the word [start] is in: those whose boxes, widened by [WORD_GAP], overlap, directly or through others. */
    private fun wordOf(strokes: List<Stroke>, isLetter: BooleanArray, start: Int): List<Int> {
        val word = arrayListOf(start)
        val inWord = BooleanArray(strokes.size).also { it[start] = true }
        var next = 0
        while (next < word.size) {
            val box = strokes[word[next++]].bounds
            for (j in strokes.indices) {
                if (inWord[j] || !isLetter[j]) continue
                val other = strokes[j].bounds
                if (box.left - WORD_GAP <= other.right && other.left - WORD_GAP <= box.right &&
                    box.top - WORD_GAP <= other.bottom && other.top - WORD_GAP <= box.bottom
                ) {
                    inWord[j] = true
                    word += j
                }
            }
        }
        return word
    }

    /** Whether at least half of [box] is inside [area]; for a box with no area (a dot, a straight line), half its length. */
    private fun mostlyInside(box: InkRect, area: InkRect): Boolean {
        val overlapWidth = min(box.right, area.right) - max(box.left, area.left)
        val overlapHeight = min(box.bottom, area.bottom) - max(box.top, area.top)
        if (overlapWidth < 0f || overlapHeight < 0f) return false
        val boxArea = box.width * box.height
        if (boxArea < MIN_AREA) {
            val length = max(box.width, box.height)
            return length == 0f || max(overlapWidth, overlapHeight) >= length / 2
        }
        return overlapWidth * overlapHeight >= boxArea / 2
    }

    /** Units a frame is widened by before choosing strokes: the model's corners tend to cut the drawing's edges. */
    const val SLACK = 2f

    /** Strokes no larger than this, in page units, may be letters: a line of the paper is 40. */
    const val LETTER_SIZE = 48f

    /** Letters closer than this, in page units, belong to the same word; the space between words is wider. */
    const val WORD_GAP = 4f

    private const val MIN_AREA = 1f
}
