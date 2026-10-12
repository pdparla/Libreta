package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/** The distance from ([x], [y]) to the nearest point of the stroke's line, found the long way. */
private fun lineDistance(stroke: Stroke, x: Float, y: Float): Double {
    fun segment(a: InkPoint, b: InkPoint): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else (((x - a.x) * dx + (y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return Math.hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }
    if (stroke.points.size == 1) return segment(stroke.points[0], stroke.points[0])
    return (1 until stroke.points.size).minOf { segment(stroke.points[it - 1], stroke.points[it]) }
}

/** Whether the line of [stroke] has a sample in, or a segment crossing, [area] grown by half its thickness; sampled densely. */
private fun touchesTheLongWay(stroke: Stroke, area: InkRect): Boolean {
    val reach = stroke.width / 2
    val steps = 400
    for (i in 1 until stroke.points.size.coerceAtLeast(2)) {
        val a = stroke.points[(i - 1).coerceAtMost(stroke.points.size - 1)]
        val b = stroke.points[min(i, stroke.points.size - 1)]
        for (s in 0..steps) {
            val x = a.x + (b.x - a.x) * s / steps
            val y = a.y + (b.y - a.y) * s / steps
            if (x >= area.left - reach && x <= area.right + reach && y >= area.top - reach && y <= area.bottom + reach) return true
        }
    }
    return false
}

private fun randomStroke(random: Random, width: Float): Stroke {
    val count = 1 + random.nextInt(5)
    val points = (0 until count).map { InkPoint(random.nextInt(200).toFloat(), random.nextInt(200).toFloat(), it * 8) }
    return Stroke(BLACK_INK, width, 1_000, points)
}

private const val BLACK_INK = 0xff202124.toInt()

class InkPrefilterTest {

    @Test
    fun `the eraser box test never rules out a stroke the eraser can reach`() {
        val random = Random(7)
        repeat(3_000) {
            val stroke = randomStroke(random, width = 1f + random.nextInt(8))
            val x = random.nextInt(260) - 30f
            val y = random.nextInt(260) - 30f
            val radius = 1f + random.nextInt(30)
            if (stroke.isOutsideDisc(x, y, radius)) {
                assertTrue("distance for $stroke at $x,$y,$radius", lineDistance(stroke, x, y) > radius + stroke.width / 2 - 0.001)
                assertFalse(stroke.isHitBy(x, y, radius))
                assertSame(stroke, stroke.erasedAt(x, y, radius).single())
            }
        }
    }

    @Test
    fun `a disc just touching the edge of the stroke is still a hit`() {
        val line = stroke(0f, 0f, 100f, 0f, width = 4f) // the box reaches y = 2

        assertTrue(line.isHitBy(50f, 12f, radius = 10f))  // 12 - 10 = 2: touching
        assertTrue(line.isHitBy(112f, 0f, radius = 10f))  // 112 - 10 = 102 = right edge
        assertFalse(line.isHitBy(50f, 12.5f, radius = 10f))
        assertEquals(2, line.erasedAt(50f, 0f, radius = 10f).size)
    }

    @Test
    fun `selecting finds the same strokes as testing every sample`() {
        val random = Random(11)
        repeat(3_000) {
            val stroke = randomStroke(random, width = 1f + random.nextInt(6))
            val left = random.nextInt(220) - 10f
            val top = random.nextInt(220) - 10f
            val area = InkRect(left, top, left + random.nextInt(60), top + random.nextInt(60))
            // Dense sampling can only miss a touch, never invent one: whatever it finds, touches must find too.
            if (touchesTheLongWay(stroke, area)) assertTrue("$stroke in $area", stroke.touches(area))
            // And a stroke whose box clearly misses the area is never selected.
            val box = stroke.bounds
            if (box.right < area.left - 0.1f || box.left > area.right + 0.1f || box.bottom < area.top - 0.1f || box.top > area.bottom + 0.1f) {
                assertFalse(stroke.touches(area))
            }
        }
    }

    @Test
    fun `a long diagonal band crosses an area with no point inside it`() {
        val band = stroke(0f, 0f, 200f, 200f, width = 4f)

        assertTrue(band.touches(InkRect(90f, 90f, 110f, 110f)))
        assertFalse(band.touches(InkRect(150f, 20f, 190f, 60f)))
        assertTrue(stroke(0f, 50f, 200f, 50f).touches(InkRect(90f, 40f, 110f, 60f)))
    }

    @Test
    fun `an eraser that misses everything changes nothing and leaves no undo step`() {
        val editor = InkEditor(InkDocument.blank(200, 200))
        editor.addStroke(stroke(10f, 10f, 50f, 10f))
        editor.addStroke(stroke(10f, 100f, 50f, 100f))
        val before = editor.document
        val undoable = editor.canUndo

        assertFalse(editor.eraseAreaAt(150f, 150f, radius = 5f))
        assertFalse(editor.eraseStrokesAt(150f, 150f, radius = 5f))

        assertSame(before, editor.document)
        assertEquals(undoable, editor.canUndo)
        editor.undo()
        assertEquals(1, editor.page.strokes.size)
    }

    @Test
    fun `area eraser keeps untouched strokes in order around the ones it cuts`() {
        val editor = InkEditor(InkDocument.blank(200, 200))
        val first = stroke(0f, 20f, 100f, 20f)
        val cut = stroke(0f, 100f, 100f, 100f)
        val gone = stroke(50f, 100f)
        val last = stroke(0f, 180f, 100f, 180f)
        listOf(first, cut, gone, last).forEach(editor::addStroke)

        assertTrue(editor.eraseAreaAt(50f, 100f, radius = 10f))

        val strokes = editor.page.strokes
        assertEquals(4, strokes.size) // first, the two halves of the cut one, last
        assertSame(first, strokes[0])
        assertSame(last, strokes[3])
        assertEquals(listOf(0f, 100f), listOf(strokes[1].points.first().x, strokes[2].points.last().x).map { Math.round(it).toFloat() })
    }

    @Test
    fun `an edit on one page keeps the marks of the others and drops those of its own that lost their strokes`() {
        val editor = InkEditor(InkDocument.blank(200, 200))
        val title = stroke(10f, 10f, 60f, 10f)
        editor.addStroke(title)
        editor.addMark(InkMark.Kind.TITLE, "Hello", listOf(0))
        editor.addPage()
        editor.addStroke(stroke(10f, 10f, 60f, 10f))
        editor.goToPage(0)

        editor.deleteStrokes(listOf(0))

        assertTrue(editor.document.pages[0].marks.isEmpty())
        assertEquals(1, editor.document.pages[1].strokes.size)
    }

    @Test
    fun `filling a shape off to one side reaches only the shape`() {
        val editor = InkEditor(InkDocument.blank(400, 300, Paper.BLANK))
        // A square outline at the right-hand end of the page.
        editor.addStroke(stroke(300f, 100f, 360f, 100f, 360f, 160f, 300f, 160f, 300f, 100f, width = 3f))

        assertTrue(editor.fill(330f, 130f, 0xffffeb3b.toInt(), zoom = 1f, startedAt = 5_000))

        val bands = editor.page.strokes.filter { it.fill }
        assertNotNull(bands.firstOrNull())
        assertTrue(bands.all { band ->
            val a = band.points.first()
            val b = band.points.last()
            min(a.x, b.x) >= 298f && max(a.x, b.x) <= 362f && a.y in 98f..162f
        })
        // The first band sits on the top row of the area and the last on the bottom one.
        assertTrue(bands.minOf { it.points.first().y } < 106f)
        assertTrue(bands.maxOf { it.points.first().y } > 154f)
    }
}
