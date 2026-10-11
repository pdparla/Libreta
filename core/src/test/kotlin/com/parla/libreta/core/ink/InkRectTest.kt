package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkRectTest {

    private val page = InkPage(600, 900, Paper.LINED, listOf(stroke(100f, 100f, 140f, 120f, width = 4f)))

    @Test
    fun `corners in any order, cut to the page`() =
        assertEquals(InkRect(0f, 50f, 300f, 900f), InkRect.between(300f, 950f, -20f, 50f, page))

    @Test
    fun `a tap is not a usable selection`() {
        assertFalse(InkRect.between(10f, 10f, 15f, 200f, page).isUsable)
        assertTrue(InkRect.between(10f, 10f, 40f, 40f, page).isUsable)
    }

    @Test
    fun `ink inside counts the stroke thickness`() {
        assertTrue(InkRect(90f, 90f, 110f, 110f).hasInk(page))
        assertTrue(InkRect(141f, 121f, 200f, 200f).hasInk(page))
        assertFalse(InkRect(150f, 130f, 200f, 200f).hasInk(page))
    }

    @Test
    fun `render scale keeps the longer side within the limit`() {
        assertEquals(2f, InkRect(0f, 0f, 300f, 200f).renderScale(2f, 1600), 0f)
        assertEquals(1600f / 900f, InkRect(0f, 0f, 600f, 900f).renderScale(2f, 1600), 0.0001f)
    }

    @Test
    fun `a rectangle selects whole strokes with ink inside`() {
        val page = InkPage(600, 900, Paper.LINED, listOf(stroke(10f, 10f, 200f, 10f), stroke(300f, 300f, 310f, 310f), stroke(15f, 100f)))

        assertEquals(listOf(0, 2), InkRect(0f, 0f, 50f, 150f).selectedStrokes(page))
        assertEquals(InkRect(8.5f, 8.5f, 201.5f, 101.5f), InkRect.around(listOf(page.strokes[0], page.strokes[2])))
        assertEquals(null, InkRect.around(emptyList()))
    }

    @Test
    fun `moving stops at the edges of the page`() {
        val page = InkPage(600, 900, Paper.LINED)
        val area = InkRect(100f, 100f, 200f, 150f)

        assertEquals(-100f to -100f, area.moveWithin(-500f, -500f, page))
        assertEquals(400f to 750f, area.moveWithin(1000f, 1000f, page))
        assertEquals(30f to -20f, area.moveWithin(30f, -20f, page))
    }

    @Test
    fun `scaling stops at the edges of the page and at a minimum size`() {
        val page = InkPage(600, 900, Paper.LINED)
        val area = InkRect(100f, 100f, 200f, 150f)

        assertEquals(5f, area.scaleWithin(10f, page), 0.0001f)
        assertEquals(0.12f, area.scaleWithin(0.01f, page), 0.0001f)
        assertEquals(1.5f, area.scaleWithin(1.5f, page), 0f)
        assertEquals(InkRect(110f, 90f, 260f, 165f), area.transformed(10f, -10f, 1.5f))
    }
}

