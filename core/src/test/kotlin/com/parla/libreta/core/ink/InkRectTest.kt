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
}
