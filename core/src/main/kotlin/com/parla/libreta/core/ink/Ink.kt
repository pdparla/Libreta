package com.parla.libreta.core.ink

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
 * @param fill whether the stroke is one band of a fill (docs/15-relleno.md): the bands of one fill share [startedAt].
 */
data class Stroke(val color: Int, val width: Float, val startedAt: Long, val points: List<InkPoint>, val fill: Boolean = false) {

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

    /**
     * Whether some of the stroke, or its thickness around it, falls inside [area]: a point, or a line between two
     * (the long bands of a fill cross a selection with no point in it).
     */
    fun touches(area: InkRect): Boolean {
        // [bounds] already counts the thickness: a stroke whose box misses the area cannot touch it.
        val box = bounds
        if (box.right < area.left - BOX_MARGIN || box.left > area.right + BOX_MARGIN ||
            box.bottom < area.top - BOX_MARGIN || box.top > area.bottom + BOX_MARGIN
        ) {
            return false
        }
        val reach = width / 2
        val left = area.left - reach
        val top = area.top - reach
        val right = area.right + reach
        val bottom = area.bottom + reach
        for (point in points) {
            if (point.x in left..right && point.y in top..bottom) return true
        }
        for (i in 1 until points.size) {
            if (crosses(points[i - 1], points[i], left, top, right, bottom)) return true
        }
        return false
    }

    /** Whether segment [a]-[b] crosses the rectangle (Liang-Barsky clipping). */
    private fun crosses(a: InkPoint, b: InkPoint, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        var from = 0f
        var to = 1f
        val dx = b.x - a.x
        val dy = b.y - a.y
        for (side in 0..3) {
            val p = when (side) {
                0 -> -dx
                1 -> dx
                2 -> -dy
                else -> dy
            }
            val q = when (side) {
                0 -> a.x - left
                1 -> right - a.x
                2 -> a.y - top
                else -> bottom - a.y
            }
            if (p == 0f) {
                if (q < 0f) return false
                continue
            }
            val r = q / p
            if (p < 0f) from = max(from, r) else to = min(to, r)
            if (from > to) return false
        }
        return true
    }

    /**
     * Whether the disc of [radius] around ([x], [y]) is clearly clear of the stroke's box, so that the eraser cannot
     * hit it: the cheap test to run before walking every segment. Slightly conservative at the edge.
     */
    internal fun isOutsideDisc(x: Float, y: Float, radius: Float): Boolean {
        val box = bounds
        return x + radius < box.left - BOX_MARGIN || x - radius > box.right + BOX_MARGIN ||
            y + radius < box.top - BOX_MARGIN || y - radius > box.bottom + BOX_MARGIN
    }

    /** Whether the pen passed within [radius] of ([x], [y]), counting the stroke's own thickness. */
    fun isHitBy(x: Float, y: Float, radius: Float): Boolean {
        if (isOutsideDisc(x, y, radius)) return false
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
        if (isOutsideDisc(x, y, radius)) return listOf(this)
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
            Stroke(color, width, startedAt + start, piece.map { it.copy(t = it.t - start) }, fill)
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

        /** Slack, in page units, on box tests: far above float rounding, so a prefilter never changes a result. */
        const val BOX_MARGIN = 0.01f
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

data class InkPage(
    val width: Int,
    val height: Int,
    val paper: Paper,
    val strokes: List<Stroke> = emptyList(),
    val marks: List<InkMark> = emptyList(),
) {

    init {
        require(width > 0 && height > 0) { "Page size must be positive" }
    }

    val isEmpty: Boolean get() = strokes.isEmpty()

    /** [indexes] plus every band of the fills they touch: a fill is erased, selected and moved whole. */
    fun withWholeFills(indexes: Collection<Int>): List<Int> {
        val fills = indexes.mapNotNullTo(HashSet()) { i -> strokes.getOrNull(i)?.takeIf { it.fill }?.startedAt }
        if (fills.isEmpty()) return indexes.toList()
        val chosen = indexes.toSet()
        return strokes.indices.filter { it in chosen || (strokes[it].fill && strokes[it].startedAt in fills) }
    }

    /** The strokes [mark] is made of that are still on the page. */
    fun strokesOf(mark: InkMark): List<Stroke> = strokes.filter { it.startedAt in mark.strokes }

    /** The rectangle around [mark]'s strokes: it follows them when they move. Null once they are all gone. */
    fun boundsOf(mark: InkMark): InkRect? = InkRect.around(strokesOf(mark))

    /** The page with its marks kept to the strokes still on it; a mark with none left is gone (docs/13-indice.md). */
    fun withLiveMarks(): InkPage {
        if (marks.isEmpty()) return this
        val present = strokes.mapTo(HashSet()) { it.startedAt }
        val live = marks.mapNotNull { mark ->
            val kept = mark.strokes.filterTo(LinkedHashSet()) { it in present }
            if (kept.isEmpty()) null else if (kept.size == mark.strokes.size) mark else mark.copy(strokes = kept)
        }
        return if (live == marks) this else copy(marks = live)
    }
}

/**
 * Handwriting marked as a title or a tag (docs/13-indice.md): its [text], as read by the AI and checked by the user,
 * and the strokes it is made of, by their [Stroke.startedAt], which moving or recolouring them does not change.
 */
data class InkMark(val kind: Kind, val text: String, val strokes: Set<Long>) {
    enum class Kind(val code: String) { TITLE("title"), TAG("tag") }

    companion object {
        private val WHITESPACE = Regex("""\s+""")

        /** A tag as stored and shown: lower case, without `#`, words joined by `-`. */
        fun tagText(raw: String): String =
            raw.trim().removePrefix("#").lowercase().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString("-")
    }
}

/** What a handwritten note's Markdown shows of its marks: the titles with their 1-based page, and the tags. */
data class InkOutline(val titles: List<Pair<String, Int>>, val tags: List<String>) {
    companion object {
        val EMPTY = InkOutline(emptyList(), emptyList())

        /** Titles in page order, top to bottom within a page; tags in order, without repeats. */
        fun of(document: InkDocument): InkOutline {
            val titles = document.pages.flatMapIndexed { i, page ->
                page.marks.filter { it.kind == InkMark.Kind.TITLE }
                    .sortedBy { page.boundsOf(it)?.top ?: 0f }
                    .map { it.text to i + 1 }
            }
            val tags = document.pages.flatMap { page -> page.marks.filter { it.kind == InkMark.Kind.TAG }.map { it.text } }.distinct()
            return InkOutline(titles, tags)
        }
    }
}

/** A handwritten note: one or more pages, each with its own size (upright or landscape). */
data class InkDocument(val pages: List<InkPage>) {

    init {
        require(pages.isNotEmpty()) { "A document has at least one page" }
    }

    companion object {
        fun blank(width: Int, height: Int, paper: Paper = Paper.LINED) = InkDocument(listOf(InkPage(width, height, paper)))
    }
}
