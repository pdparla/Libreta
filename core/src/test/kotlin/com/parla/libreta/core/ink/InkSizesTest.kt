package com.parla.libreta.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkSizesTest {

    @Test
    fun `the slider covers the range smoothly, from end to end`() {
        val sizes = InkSizes.PEN

        assertEquals(0.5f, sizes.valueAt(0), 0.0001f)
        assertEquals(40f, sizes.valueAt(InkSizes.STEPS), 0.001f)
        val values = (0..InkSizes.STEPS).map(sizes::valueAt)
        assertTrue(values.zipWithNext().all { (a, b) -> b > a && b / a < 1.01f })
    }

    @Test
    fun `thin sizes get as much travel as thick ones`() {
        val sizes = InkSizes.PEN

        val thick = sizes.progressOf(40f) - sizes.progressOf(13.33f)
        val thin = sizes.progressOf(3f) - sizes.progressOf(1f)
        assertTrue("$thick vs $thin", kotlin.math.abs(thick - thin) <= 2)
    }

    @Test
    fun `a value and its position go both ways, and old fixed sizes fit`() {
        for (value in listOf(2f, 3.5f, 6f)) assertEquals(value, InkSizes.PEN.valueAt(InkSizes.PEN.progressOf(value)), value * 0.005f)
        for (value in listOf(5f, 10f, 22f)) assertEquals(value, InkSizes.ERASER.valueAt(InkSizes.ERASER.progressOf(value)), value * 0.005f)
        assertEquals(0, InkSizes.ERASER.progressOf(0.1f))
        assertEquals(InkSizes.STEPS, InkSizes.ERASER.progressOf(500f))
    }
}
