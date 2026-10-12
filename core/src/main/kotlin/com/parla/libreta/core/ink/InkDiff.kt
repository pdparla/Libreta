package com.parla.libreta.core.ink

import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * What to redraw when a page on screen changes into another version of itself: a page full of ink takes a while to
 * draw on a slow tablet, and most edits touch a small part of it.
 */
object InkDiff {

    /** Space around a mark's ink that its highlight covers, in page units. */
    const val MARK_PADDING = 4f

    /** Whether [a] and [b] are laid out alike: the same size and paper, so that only strokes and marks can differ. */
    fun sameCanvas(a: InkPage, b: InkPage): Boolean = a.width == b.width && a.height == b.height && a.paper == b.paper

    /**
     * The rectangle, in page units, that covers everything that looks different between [before] and [after], two
     * versions of a page with the same canvas (see [sameCanvas]); null when nothing does. Over-sizing is harmless and
     * an under-sized rectangle leaves ghost ink, so it errs on the large side:
     * - strokes in only one of the two pages, by identity (a stroke's equality walks all its points, and the strokes
     *   an edit leaves alone are the same objects), counting repeats: the same object twice is two strokes;
     * - the highlight of every mark whose strokes changed or that was added, removed or edited, as it was and as it is;
     * - [hidden]: strokes that [before] was drawn without (the ones being moved), which are drawn again now.
     */
    fun dirtyArea(before: InkPage, after: InkPage, hidden: List<Stroke> = emptyList()): InkRect? {
        if (before === after && hidden.isEmpty()) return null
        var area: InkRect? = null
        for (stroke in hidden) area = area.union(stroke.bounds)
        if (before !== after) {
            // Strokes of before count up, those of after down: what is left over went or came.
            val balance = IdentityHashMap<Stroke, Int>()
            for (stroke in before.strokes) balance[stroke] = (balance[stroke] ?: 0) + 1
            for (stroke in after.strokes) balance[stroke] = (balance[stroke] ?: 0) - 1
            // A mark's highlight follows its strokes, which are told apart by when they began: moving or recolouring
            // one keeps that, and the pieces of an erased one began later.
            val changed = HashSet<Long>()
            for ((stroke, count) in balance) {
                if (count == 0) continue
                area = area.union(stroke.bounds)
                changed += stroke.startedAt
            }
            if (changed.isNotEmpty() || before.marks != after.marks) {
                for (mark in before.marks) {
                    if (mark in after.marks && mark.strokes.none { it in changed }) continue
                    area = area.union(before.highlightOf(mark))
                }
                for (mark in after.marks) {
                    if (mark in before.marks && mark.strokes.none { it in changed }) continue
                    area = area.union(after.highlightOf(mark))
                }
            }
        }
        return area
    }

    /** The rectangle [mark] is highlighted in on this page; null once its strokes are gone. */
    private fun InkPage.highlightOf(mark: InkMark): InkRect? =
        boundsOf(mark)?.let { InkRect(it.left - MARK_PADDING, it.top - MARK_PADDING, it.right + MARK_PADDING, it.bottom + MARK_PADDING) }

    private fun InkRect?.union(other: InkRect?): InkRect? = when {
        other == null -> this
        this == null -> other
        else -> InkRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    }
}
