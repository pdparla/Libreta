package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkDiffTest {

    private val a = stroke(10f, 10f, 50f, 10f, width = 2f).copy(startedAt = 1)
    private val b = stroke(100f, 100f, 150f, 120f, width = 2f).copy(startedAt = 2)
    private val c = stroke(300f, 400f, 340f, 420f, width = 2f).copy(startedAt = 3)
    private val page = InkPage(600, 900, Paper.LINED, listOf(a, b, c))

    @Test
    fun `the same page needs nothing`() {
        assertNull(InkDiff.dirtyArea(page, page))
        assertNull(InkDiff.dirtyArea(page, page.copy()))
    }

    @Test
    fun `a stroke that came or went is its own bounds`() {
        val d = stroke(200f, 200f, 220f, 260f, width = 4f).copy(startedAt = 4)

        assertEquals(d.bounds, InkDiff.dirtyArea(page, page.copy(strokes = page.strokes + d)))
        assertEquals(b.bounds, InkDiff.dirtyArea(page, page.copy(strokes = listOf(a, c))))
    }

    @Test
    fun `a moved or recoloured stroke covers where it was and where it is`() {
        val moved = b.transformed(200f, 50f)
        val recoloured = b.copy(color = 0xffff0000.toInt())

        assertEquals(InkRect(99f, 99f, 351f, 171f), InkDiff.dirtyArea(page, page.copy(strokes = listOf(a, moved, c))))
        assertEquals(b.bounds, InkDiff.dirtyArea(page, page.copy(strokes = listOf(a, recoloured, c))))
    }

    @Test
    fun `strokes are told apart by identity, not by what they hold`() {
        // Equal by value, a different object: it is redrawn.
        val copy = b.copy()

        assertEquals(b.bounds, InkDiff.dirtyArea(page, page.copy(strokes = listOf(a, copy, c))))
    }

    @Test
    fun `the same stroke pasted again is a change`() {
        assertEquals(c.bounds, InkDiff.dirtyArea(page, page.copy(strokes = page.strokes + c)))
        assertEquals(a.bounds, InkDiff.dirtyArea(page.copy(strokes = page.strokes + a), page))
    }

    @Test
    fun `strokes that moved in the order but not on the page are not redrawn`() {
        val d = stroke(200f, 200f, 220f, 260f, width = 4f).copy(startedAt = 4)

        // Like a fill, which goes in under the ink: only the new stroke counts.
        assertEquals(d.bounds, InkDiff.dirtyArea(page, page.copy(strokes = listOf(d, a, b, c))))
    }

    @Test
    fun `hidden strokes are redrawn even if nothing changed`() {
        assertEquals(InkRect(99f, 99f, 341f, 421f), InkDiff.dirtyArea(page, page, listOf(b, c)))
        assertEquals(b.bounds, InkDiff.dirtyArea(page, page.copy(), listOf(b)))
    }

    @Test
    fun `hidden strokes and their move are both covered`() {
        val moved = b.transformed(-60f, -60f)

        val area = InkDiff.dirtyArea(page, page.copy(strokes = listOf(a, moved, c)), listOf(b))!!

        assertEquals(InkRect(39f, 39f, 151f, 121f), area)
    }

    @Test
    fun `a new mark is its highlight, padded`() {
        val marked = page.copy(marks = listOf(InkMark(InkMark.Kind.TITLE, "t", setOf(2L, 3L))))
        val bounds = InkRect.around(listOf(b, c))!!

        assertEquals(InkRect(bounds.left - 4f, bounds.top - 4f, bounds.right + 4f, bounds.bottom + 4f), InkDiff.dirtyArea(page, marked))
        assertEquals(InkDiff.dirtyArea(page, marked), InkDiff.dirtyArea(marked, page))
    }

    @Test
    fun `a mark changed in kind or text covers its old and new place`() {
        val title = page.copy(marks = listOf(InkMark(InkMark.Kind.TITLE, "t", setOf(1L))))
        val tag = page.copy(marks = listOf(InkMark(InkMark.Kind.TAG, "t", setOf(1L, 2L))))
        val reach = InkRect.around(listOf(a, b))!!

        assertEquals(InkRect(reach.left - 4f, reach.top - 4f, reach.right + 4f, reach.bottom + 4f), InkDiff.dirtyArea(title, tag))
    }

    @Test
    fun `a mark follows its strokes when they move`() {
        val marked = page.copy(marks = listOf(InkMark(InkMark.Kind.TAG, "t", setOf(1L, 2L))))
        val moved = marked.copy(strokes = listOf(a.transformed(0f, 300f), b, c))

        val area = InkDiff.dirtyArea(marked, moved)!!

        // Where the mark was (a and b, padded), and where it is now (b and the moved a, padded).
        assertTrue(area.contains(6f, 6f))
        assertTrue(area.contains(51f, 311f))
        assertTrue(area.contains(151f, 121f))
    }

    @Test
    fun `a highlight that shrinks or goes with erased ink is redrawn`() {
        val marked = page.copy(marks = listOf(InkMark(InkMark.Kind.TITLE, "t", setOf(1L, 3L))))
        val wide = InkRect.around(listOf(a, c))!!
        val without = marked.copy(strokes = listOf(a, b)).withLiveMarks()
        val gone = marked.copy(strokes = listOf(b)).withLiveMarks()

        // c went and the mark, now only a, shrank: the whole old highlight is stale.
        val shrunk = InkDiff.dirtyArea(marked, without)!!
        assertTrue(shrunk.contains(wide.right + 3f, wide.bottom + 3f))
        assertTrue(shrunk.contains(wide.left - 3f, wide.top - 3f))
        // Every stroke of the mark went with it.
        assertTrue(InkDiff.dirtyArea(marked, gone)!!.contains(wide.left - 3f, wide.top - 3f))
        assertEquals(0, gone.marks.size)
    }

    @Test
    fun `an erased stroke leaves pieces that begin later`() {
        val marked = page.copy(marks = listOf(InkMark(InkMark.Kind.TITLE, "t", setOf(2L))))
        val pieces = b.erasedAt(125f, 110f, 6f)
        assertTrue(pieces.size == 2)
        val erased = marked.copy(strokes = listOf(a) + pieces + c).withLiveMarks()

        val area = InkDiff.dirtyArea(marked, erased)!!

        assertTrue(area.contains(100f, 100f))
        assertTrue(area.contains(150f, 120f))
        assertFalse(area.contains(300f, 400f))
    }

    @Test
    fun `only the canvas decides whether pages can be compared`() {
        assertTrue(InkDiff.sameCanvas(page, page.copy(strokes = emptyList())))
        assertFalse(InkDiff.sameCanvas(page, page.copy(paper = Paper.GRID)))
        assertFalse(InkDiff.sameCanvas(page, page.copy(width = 601)))
        assertFalse(InkDiff.sameCanvas(page, page.copy(height = 901)))
    }
}
