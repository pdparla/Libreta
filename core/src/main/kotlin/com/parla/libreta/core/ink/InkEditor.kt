package com.parla.libreta.core.ink

/**
 * Editing session on an [InkDocument]: adding and erasing strokes, pages, and undo/redo. Every change produces a
 * new immutable document, so undo is just going back to the previous one.
 */
class InkEditor(initial: InkDocument, private val maxUndo: Int = 100) {

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var saved: InkDocument = initial
    private var gestureStart: Snapshot? = null

    private data class Snapshot(val document: InkDocument, val pageIndex: Int)

    var document: InkDocument = initial
        private set

    var pageIndex: Int = 0
        private set

    val page: InkPage get() = document.pages[pageIndex]

    val pageCount: Int get() = document.pages.size

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Whether the document differs from the one last saved (or opened). */
    val isModified: Boolean get() = document != saved

    /** The current document is now the saved one; undo history is kept. */
    fun markSaved() {
        saved = document
    }

    fun addStroke(stroke: Stroke) = change { pages ->
        pages[pageIndex] = page.copy(strokes = page.strokes + stroke)
    }

    /**
     * Removes every stroke on the current page that the eraser at ([x], [y]) touches, and the whole of any fill it
     * touches (docs/15-relleno.md). Returns whether any did.
     */
    fun eraseStrokesAt(x: Float, y: Float, radius: Float): Boolean {
        val strokes = page.strokes
        var touched: ArrayList<Int>? = null
        for (i in strokes.indices) {
            if (!strokes[i].isHitBy(x, y, radius)) continue
            (touched ?: ArrayList<Int>().also { touched = it }).add(i)
        }
        // Most touch points hit nothing: that costs no allocation beyond the scan.
        val hitIndexes = touched ?: return false
        val hit = page.withWholeFills(hitIndexes).toSet()
        val remaining = strokes.filterIndexed { i, _ -> i !in hit }
        change { pages -> pages[pageIndex] = page.copy(strokes = remaining) }
        return true
    }

    /**
     * Rubs out only the ink under the eraser at ([x], [y]), cutting the strokes it crosses in two. Returns whether
     * anything was erased.
     */
    fun eraseAreaAt(x: Float, y: Float, radius: Float): Boolean {
        val strokes = page.strokes
        // The new list is only started at the first stroke the eraser changes; most touch points change none.
        var remaining: ArrayList<Stroke>? = null
        for (i in strokes.indices) {
            val stroke = strokes[i]
            if (stroke.isOutsideDisc(x, y, radius)) {
                remaining?.add(stroke)
                continue
            }
            val pieces = stroke.erasedAt(x, y, radius)
            if (pieces.size == 1 && pieces[0] === stroke) {
                remaining?.add(stroke)
                continue
            }
            val out = remaining ?: ArrayList<Stroke>(strokes.size).also {
                it.addAll(strokes.subList(0, i))
                remaining = it
            }
            out.addAll(pieces)
        }
        val result = remaining ?: return false
        change { pages -> pages[pageIndex] = page.copy(strokes = result) }
        return true
    }

    /**
     * Groups every change until [endGesture] into one undo step, so that rubbing across the page is undone in one
     * go rather than one touch point at a time.
     */
    fun beginGesture() {
        endGesture()
        gestureStart = Snapshot(document, pageIndex)
    }

    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start.document !== document) push(start)
    }

    /**
     * Scales the strokes at [indexes] of the current page by [scale] around ([pivotX], [pivotY]) and moves them by
     * ([dx], [dy]), in one undo step. The strokes keep their place in the page's order.
     */
    fun transformStrokes(indexes: Collection<Int>, dx: Float, dy: Float, scale: Float = 1f, pivotX: Float = 0f, pivotY: Float = 0f) {
        if (indexes.isEmpty() || (dx == 0f && dy == 0f && scale == 1f)) return
        replaceStrokes(indexes) { it.transformed(dx, dy, scale, pivotX, pivotY) }
    }

    /** Gives the strokes at [indexes] a new [color] and/or [width], in one undo step. */
    fun restyleStrokes(indexes: Collection<Int>, color: Int? = null, width: Float? = null) {
        replaceStrokes(indexes) { it.copy(color = color ?: it.color, width = width ?: it.width) }
    }

    /**
     * Marks the strokes at [indexes] of the current page as a title or a tag with [text], in one undo step. Strokes
     * already in a mark of the same kind leave it, so that marking again replaces.
     */
    fun addMark(kind: InkMark.Kind, text: String, indexes: Collection<Int>) {
        val ids = indexes.mapNotNull { page.strokes.getOrNull(it)?.startedAt }.toCollection(LinkedHashSet())
        if (ids.isEmpty() || text.isBlank()) return
        change { pages ->
            val others = page.marks.map { if (it.kind == kind) it.copy(strokes = it.strokes - ids) else it }.filter { it.strokes.isNotEmpty() }
            pages[pageIndex] = page.copy(marks = others + InkMark(kind, text.trim(), ids))
        }
    }

    /** Removes the strokes at [indexes] of the current page, in one undo step. */
    fun deleteStrokes(indexes: Collection<Int>) {
        if (indexes.isEmpty()) return
        val doomed = indexes.toSet()
        change { pages -> pages[pageIndex] = page.copy(strokes = page.strokes.filterIndexed { i, _ -> i !in doomed }) }
    }

    /**
     * Fills the area around ([x], [y]) of the current page with [color], seen at [zoom], in one undo step
     * (docs/15-relleno.md). The new bands go behind the ink, after the fills already at the bottom of the page, and
     * replace the fill bands inside the area; an older band that crosses it keeps its pieces outside. Returns false when there is nothing to fill there (the point is on ink).
     */
    fun fill(x: Float, y: Float, color: Int, zoom: Float, startedAt: Long): Boolean {
        val result = InkFill.at(page, x, y, color, zoom, startedAt) ?: return false
        if (result.bands.isEmpty()) return false
        val kept = page.strokes.flatMapIndexed { i, stroke -> result.replaced[i] ?: listOf(stroke) }
        val bottom = kept.indexOfFirst { !it.fill }.let { if (it < 0) kept.size else it }
        change { pages -> pages[pageIndex] = page.copy(strokes = kept.subList(0, bottom) + result.bands + kept.subList(bottom, kept.size)) }
        return true
    }

    /** Adds [strokes] on top of the current page, in one undo step; returns their indexes there. */
    fun insertStrokes(strokes: List<Stroke>): List<Int> {
        if (strokes.isEmpty()) return emptyList()
        val first = page.strokes.size
        change { pages -> pages[pageIndex] = page.copy(strokes = page.strokes + strokes) }
        return (first until first + strokes.size).toList()
    }

    private inline fun replaceStrokes(indexes: Collection<Int>, update: (Stroke) -> Stroke) {
        val chosen = indexes.toSet()
        val updated = page.strokes.mapIndexed { i, stroke -> if (i in chosen) update(stroke) else stroke }
        if (updated != page.strokes) change { pages -> pages[pageIndex] = page.copy(strokes = updated) }
    }

    /**
     * Undoes whatever happened since [beginGesture] and leaves no undo step: a touch that turned out to be the
     * start of a two-finger zoom, not an erasure.
     */
    fun cancelGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        document = start.document
        pageIndex = start.pageIndex
    }

    /**
     * Adds a page after the current one, with the same paper, and moves to it. Same size as the current one unless
     * told otherwise: an upright note can get a landscape page (docs/10-orientacion.md).
     */
    fun addPage(width: Int = page.width, height: Int = page.height) = change(newPageIndex = pageIndex + 1) { pages ->
        pages.add(pageIndex + 1, InkPage(width, height, page.paper))
    }

    fun setPaper(paper: Paper) {
        if (page.paper != paper) change { pages -> pages[pageIndex] = page.copy(paper = paper) }
    }

    fun goToPage(index: Int) {
        pageIndex = index.coerceIn(0, pageCount - 1)
    }

    fun undo() {
        endGesture()
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(document, pageIndex))
        restore(previous)
    }

    fun redo() {
        endGesture()
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snapshot(document, pageIndex))
        restore(next)
    }

    private fun restore(snapshot: Snapshot) {
        document = snapshot.document
        pageIndex = snapshot.pageIndex.coerceIn(0, pageCount - 1)
    }

    private inline fun change(newPageIndex: Int = pageIndex, edit: (MutableList<InkPage>) -> Unit) {
        if (gestureStart == null) push(Snapshot(document, pageIndex))
        val pages = document.pages.toMutableList()
        edit(pages)
        // Marks whose strokes were erased go with them (docs/13-indice.md). Only pages the edit replaced can have lost any.
        val before = document.pages
        for (i in pages.indices) {
            val edited = pages[i]
            if (i >= before.size || edited !== before[i]) pages[i] = edited.withLiveMarks()
        }
        document = InkDocument(pages)
        pageIndex = newPageIndex
    }

    private fun push(snapshot: Snapshot) {
        undoStack.addLast(snapshot)
        if (undoStack.size > maxUndo) undoStack.removeFirst()
        redoStack.clear()
    }
}
