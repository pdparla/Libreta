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

    /** Removes every stroke on the current page that the eraser at ([x], [y]) touches. Returns whether any did. */
    fun eraseStrokesAt(x: Float, y: Float, radius: Float): Boolean {
        val remaining = page.strokes.filterNot { it.isHitBy(x, y, radius) }
        if (remaining.size == page.strokes.size) return false
        change { pages -> pages[pageIndex] = page.copy(strokes = remaining) }
        return true
    }

    /**
     * Rubs out only the ink under the eraser at ([x], [y]), cutting the strokes it crosses in two. Returns whether
     * anything was erased.
     */
    fun eraseAreaAt(x: Float, y: Float, radius: Float): Boolean {
        var erased = false
        val remaining = page.strokes.flatMap { stroke ->
            stroke.erasedAt(x, y, radius).also { if (it.size != 1 || it[0] !== stroke) erased = true }
        }
        if (erased) change { pages -> pages[pageIndex] = page.copy(strokes = remaining) }
        return erased
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

    /** Adds a page after the current one, with the same size and paper, and moves to it. */
    fun addPage() = change(newPageIndex = pageIndex + 1) { pages ->
        pages.add(pageIndex + 1, InkPage(page.width, page.height, page.paper))
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
        document = InkDocument(pages)
        pageIndex = newPageIndex
    }

    private fun push(snapshot: Snapshot) {
        undoStack.addLast(snapshot)
        if (undoStack.size > maxUndo) undoStack.removeFirst()
        redoStack.clear()
    }
}
