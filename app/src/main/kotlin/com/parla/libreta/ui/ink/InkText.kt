package com.parla.libreta.ui.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.parla.libreta.core.ink.InkPage
import com.parla.libreta.core.ink.InkPoint
import com.parla.libreta.core.ink.InkRect
import com.parla.libreta.core.ink.Stroke
import kotlin.math.max
import kotlin.math.min

/**
 * Typed text on a page (docs/design/ink.md): handwriting turned into a font. It is a [Stroke] with a text, whose two
 * points are the corners of its box and whose width is the font size, so `:core` can select, move and scale it
 * without knowing about fonts. This object lays it out, and draws it the same way on screen and in the page images.
 */
object InkText {

    private val TYPEFACE: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    /** Measured at a large size and scaled: text widths grow in proportion to the font size. */
    private const val MEASURE_SIZE = 100f

    /** Font sizes, in page units, that typed text is fitted to: from a small note to a big heading. */
    private const val MIN_SIZE = 8f
    private const val MAX_SIZE = 160f

    private fun linesOf(text: String) = text.split('\n')

    /** Configures [paint] to draw [stroke] at [scale] pixels per unit. */
    private fun configure(paint: Paint, stroke: Stroke, scale: Float) {
        paint.isAntiAlias = true
        paint.isSubpixelText = true
        paint.style = Paint.Style.FILL
        paint.typeface = TYPEFACE
        paint.color = stroke.color
        paint.textSize = stroke.width * scale
    }

    /** Width and height of [text] at font size 1, in page units. */
    fun sizeAtOne(text: String): Pair<Float, Float> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = TYPEFACE; textSize = MEASURE_SIZE }
        val lines = linesOf(text)
        val width = lines.maxOf { paint.measureText(it) } / MEASURE_SIZE
        return width to lines.size * paint.fontSpacing / MEASURE_SIZE
    }

    /** Typed [text] with its upper-left corner at ([left], [top]) and font size [size]. */
    fun typed(text: String, left: Float, top: Float, size: Float, color: Int, startedAt: Long): Stroke {
        val (width, height) = sizeAtOne(text)
        val corners = listOf(InkPoint(left, top, 0), InkPoint(left + max(width * size, 1f), top + max(height * size, 1f), 0))
        return Stroke(color, size, startedAt, corners, text = text)
    }

    /**
     * [text] with line breaks added so that no line is wider than [maxWidth] at font [size], breaking between words;
     * a single word wider than that stays whole. The breaks become part of the text: typed text is laid out once.
     */
    fun wrap(text: String, size: Float, maxWidth: Float): String {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = TYPEFACE; textSize = size }
        return linesOf(text).joinToString("\n") { line ->
            if (paint.measureText(line) <= maxWidth) return@joinToString line
            val out = StringBuilder()
            var current = ""
            for (word in line.split(' ')) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (current.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    out.append(current).append('\n')
                    current = word
                }
            }
            out.append(current).toString()
        }
    }

    /**
     * Typed [text] put on [page] where the user tapped, ([x], [y]): the first line centred on that height, wrapped to
     * the page's right edge, moved up if it would run past the bottom, and smaller if even then it does not fit.
     */
    fun placed(page: InkPage, text: String, x: Float, y: Float, size: Float, color: Int, startedAt: Long): Stroke {
        val left = x.coerceAtMost(page.width - EDGE - MIN_WRAP_WIDTH).coerceAtLeast(0f)
        var fontSize = size
        while (true) {
            val wrapped = wrap(text, fontSize, page.width - EDGE - left)
            val height = sizeAtOne(wrapped).second
            val lineHeight = height / linesOf(wrapped).size * fontSize
            val top = (y - lineHeight / 2).coerceAtMost(page.height - height * fontSize).coerceAtLeast(0f)
            if (height * fontSize <= page.height || fontSize <= MIN_SIZE) return typed(wrapped, left, top, fontSize, color, startedAt)
            fontSize = max(MIN_SIZE, fontSize * 0.8f)
        }
    }

    /** Room left free at the page's right edge, in page units. */
    private const val EDGE = 16f

    /** The narrowest a placed text is wrapped to: near the right edge it moves left instead. */
    private const val MIN_WRAP_WIDTH = 120f

    /**
     * Typed [text] at ([left], [top]) and font size [size], as [typed], but kept on [page]: smaller if it would run
     * past its edge, and moved in if even the smallest size does not fit. For a corrected text, which may be longer.
     */
    fun typedOn(page: InkPage, text: String, left: Float, top: Float, size: Float, color: Int, startedAt: Long): Stroke {
        val (width, height) = sizeAtOne(text)
        val fits = min((page.width - left) / max(width, 0.01f), (page.height - top) / max(height, 0.01f))
        val fitted = min(size, fits).coerceAtLeast(MIN_SIZE)
        val x = left.coerceAtMost(page.width - width * fitted).coerceAtLeast(0f)
        val y = top.coerceAtMost(page.height - height * fitted).coerceAtLeast(0f)
        return typed(text, x, y, fitted, color, startedAt)
    }

    /**
     * Typed [text] in place of the handwriting in [area]: at its upper-left corner, as large as fits in it, so that
     * the text takes about the room the handwriting took.
     */
    fun fitted(text: String, area: InkRect, color: Int, startedAt: Long): Stroke =
        typed(text, area.left, area.top, fittedSize(text, area), color, startedAt)

    /** The largest font size, within limits, at which every line of [text] fits in [area]. */
    fun fittedSize(text: String, area: InkRect): Float {
        val (width, height) = sizeAtOne(text)
        return min(area.width / max(width, 0.01f), area.height / max(height, 0.01f)).coerceIn(MIN_SIZE, MAX_SIZE)
    }

    /** Draws [stroke] (typed text) at [scale] with [paint], which it reconfigures. */
    fun draw(canvas: Canvas, stroke: Stroke, scale: Float, paint: Paint) {
        val text = stroke.text ?: return
        configure(paint, stroke, scale)
        val box = stroke.bounds
        val x = box.left * scale
        var baseline = box.top * scale - paint.ascent()
        val spacing = paint.fontSpacing
        for (line in linesOf(text)) {
            canvas.drawText(line, x, baseline, paint)
            baseline += spacing
        }
    }

    /** [stroke] (typed text) as one path of its letters and the paint to fill it, to draw it many times while it is moved. */
    fun path(stroke: Stroke, scale: Float): Pair<Path, Paint> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        configure(paint, stroke, scale)
        val path = Path()
        val box = stroke.bounds
        var baseline = box.top * scale - paint.ascent()
        val line = Path()
        for (text in linesOf(stroke.text.orEmpty())) {
            paint.getTextPath(text, 0, text.length, box.left * scale, baseline, line)
            path.addPath(line)
            baseline += paint.fontSpacing
        }
        return path to paint
    }
}
