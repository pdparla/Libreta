package com.parla.libreta.ui.ink

import com.parla.libreta.core.ink.InkPage
import com.parla.libreta.core.ink.InkRect
import com.parla.libreta.core.ink.Stroke

/**
 * Strokes copied from a selection, to paste on any page of any handwritten note. Only Libreta understands them, so
 * they stay in memory instead of the system clipboard; they are lost if the app's process ends.
 */
object InkClipboard {

    private var strokes: List<Stroke> = emptyList()

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun copy(selected: List<Stroke>) {
        strokes = selected
    }

    /** The copied strokes as they go onto [page]: a little below and to the right of where they were, kept on the page. */
    fun placedOn(page: InkPage): List<Stroke> {
        val bounds = InkRect.around(strokes) ?: return emptyList()
        val (dx, dy) = bounds.moveWithin(PASTE_OFFSET, PASTE_OFFSET, page)
        return strokes.map { it.transformed(dx, dy) }
    }

    /** Far enough that a copy pasted on the same page does not hide the original. */
    private const val PASTE_OFFSET = 16f
}
