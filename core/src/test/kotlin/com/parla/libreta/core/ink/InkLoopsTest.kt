package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class InkLoopsTest {

    /** An ellipse drawn as one stroke, leaving [gapDegrees] of it open. */
    private fun loop(cx: Float, cy: Float, rx: Float, ry: Float, gapDegrees: Double = 20.0, startedAt: Long = 100): Stroke {
        val sweep = 2 * PI - gapDegrees * PI / 180
        val points = (0..40).map { i ->
            val a = sweep * i / 40
            InkPoint(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat(), i * 10)
        }
        return Stroke(0xff202124.toInt(), 3f, startedAt, points)
    }

    private fun word(x: Float, y: Float, startedAt: Long) = stroke(x, y, x + 10f, y + 15f).copy(startedAt = startedAt)

    @Test
    fun `a circle drawn by hand is a loop, open a little or not`() {
        assertTrue(InkLoops.isLoop(loop(200f, 200f, 100f, 40f)))
        assertTrue(InkLoops.isLoop(loop(200f, 200f, 100f, 40f, gapDegrees = 0.0)))
    }

    @Test
    fun `lines, open arcs, small rings and scribbles are not`() {
        assertFalse(InkLoops.isLoop(stroke(0f, 0f, 100f, 0f, 200f, 0f, 300f, 0f, 400f, 0f, 500f, 0f, 600f, 0f, 700f, 0f)))
        assertFalse(InkLoops.isLoop(loop(200f, 200f, 100f, 40f, gapDegrees = 120.0)))
        assertFalse(InkLoops.isLoop(loop(200f, 200f, 10f, 10f))) // an "o", not a circle around anything
        // Back and forth across the same line: its ends meet but it goes round nothing.
        val scribble = (0..9).flatMap { listOf(0f + it, 0f, 60f, 20f) }.toFloatArray()
        assertFalse(InkLoops.isLoop(stroke(*scribble)))
    }

    @Test
    fun `finds what is inside the loop, not what is beside it`() {
        val circle = loop(200f, 200f, 100f, 40f, startedAt = 9)
        val page = InkPage(600, 900, Paper.LINED, listOf(word(150f, 190f, 1), word(220f, 190f, 2), word(320f, 190f, 3), circle))

        assertEquals(listOf(0, 1), InkLoops.enclosed(page, circle))
        assertSame(circle, InkLoops.circledQuestion(page))
    }

    @Test
    fun `only the most recent stroke counts, and an empty circle is no question`() {
        val circle = loop(200f, 200f, 100f, 40f)
        assertNull(InkLoops.circledQuestion(InkPage(600, 900, Paper.LINED, listOf(circle))))
        assertNull(InkLoops.circledQuestion(InkPage(600, 900, Paper.LINED, listOf(word(150f, 190f, 1), word(220f, 190f, 2), circle, word(10f, 10f, 3)))))
    }
}
